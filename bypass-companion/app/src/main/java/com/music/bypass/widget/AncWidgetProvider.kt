package com.music.bypass.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.music.bypass.MainActivity
import com.music.bypass.R
import com.music.bypass.data.AncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AncWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                com.music.bypass.service.BudsService.start(context)
                val status = AncManager.getBudsStatus(context)
                for (appWidgetId in appWidgetIds) {
                    renderWidget(context, appWidgetManager, appWidgetId, status)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_SET_MODE -> {
                val mode = intent.getStringExtra(EXTRA_MODE) ?: AncManager.MODE_ANC
                val fromRealmeLink = intent.getBooleanExtra("from_realme_link", false)
                // 1. Instant optimistic update so home screen reacts with zero lag
                AncManager.setActiveMode(context, mode)
                notifyUpdate(context)

                // 2. Perform background device switch only if triggered outside of Realme Link
                if (!fromRealmeLink) {
                    val pendingResult = goAsync()
                    scope.launch {
                        try {
                            com.music.bypass.service.BudsService.start(context)
                            AncManager.switchMode(context, mode)
                            notifyUpdate(context)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
            ACTION_REFRESH -> {
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        com.music.bypass.service.BudsService.refresh(context)
                        // Allow live RFCOMM response (approx 100-300ms) to update cache
                        delay(350)
                        val status = AncManager.getBudsStatus(context)
                        val appWidgetManager = AppWidgetManager.getInstance(context)
                        val thisWidget = ComponentName(context, AncWidgetProvider::class.java)
                        val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                        for (appWidgetId in allWidgetIds) {
                            renderWidget(context, appWidgetManager, appWidgetId, status)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            ACTION_WIDGET_UPDATE -> {
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        val status = AncManager.getBudsStatus(context)
                        val appWidgetManager = AppWidgetManager.getInstance(context)
                        val thisWidget = ComponentName(context, AncWidgetProvider::class.java)
                        val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                        for (appWidgetId in allWidgetIds) {
                            renderWidget(context, appWidgetManager, appWidgetId, status)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" -> {
                val lvl = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                if (lvl >= 0 && AncManager.getCachedBattery(context) < 0) {
                    AncManager.setCachedBattery(context, lvl)
                    notifyUpdate(context)
                }
            }
        }
    }

    companion object {
        const val ACTION_SET_MODE = "com.music.bypass.ACTION_SET_ANC_MODE"
        const val ACTION_REFRESH = "com.music.bypass.ACTION_ANC_WIDGET_REFRESH"
        const val ACTION_WIDGET_UPDATE = "com.music.bypass.ANC_WIDGET_UPDATE"
        const val EXTRA_MODE = "extra_mode"

        fun notifyUpdate(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, AncWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            if (allWidgetIds.isNotEmpty()) {
                val intent = Intent(context, AncWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, allWidgetIds)
                }
                context.sendBroadcast(intent)
            }
            try {
                val updateIntent = Intent(ACTION_WIDGET_UPDATE).apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(updateIntent)
            } catch (_: Exception) {}
        }

        fun renderWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            status: AncManager.BudsInfo
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_anc_toggle)

            // 1. Root tap opens MainActivity
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingLaunch = PendingIntent.getActivity(
                context, 100, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.anc_device_name, pendingLaunch)

            // 2. Refresh tap (both the dedicated refresh button and battery badge trigger live refresh)
            val refreshIntent = Intent(context, AncWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            val pendingRefresh = PendingIntent.getBroadcast(
                context, 101, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.anc_btn_refresh, pendingRefresh)
            views.setOnClickPendingIntent(R.id.anc_btn_refresh_battery, pendingRefresh)

            // 3. Header Info
            val shortName = if (status.name.length > 20) {
                status.name.replace("realme ", "")
            } else {
                status.name
            }
            views.setTextViewText(R.id.anc_device_name, "🎧 $shortName")

            // Status Badge
            val (statusText, statusColor) = when (status.mode) {
                AncManager.MODE_ANC -> "• ANC Active" to Color.parseColor("#4EBA6F")
                AncManager.MODE_TRANSPARENCY -> "• Ambient Active" to Color.parseColor("#60A5FA")
                else -> "• Normal (Off)" to Color.parseColor("#8B949E")
            }
            views.setTextViewText(R.id.anc_status_badge, statusText)
            views.setTextColor(R.id.anc_status_badge, statusColor)

            // Battery Display
            if (status.battery >= 0) {
                views.setTextViewText(R.id.anc_battery_text, "🔋 ${status.battery}%")
                when {
                    status.battery >= 40 -> {
                        views.setInt(R.id.anc_btn_refresh_battery, "setBackgroundResource", R.drawable.widget_battery_badge_green)
                        views.setTextColor(R.id.anc_battery_text, Color.parseColor("#4EBA6F"))
                    }
                    status.battery >= 20 -> {
                        views.setInt(R.id.anc_btn_refresh_battery, "setBackgroundResource", R.drawable.widget_battery_badge_amber)
                        views.setTextColor(R.id.anc_battery_text, Color.parseColor("#F59E0B"))
                    }
                    else -> {
                        views.setInt(R.id.anc_btn_refresh_battery, "setBackgroundResource", R.drawable.widget_battery_badge_red)
                        views.setTextColor(R.id.anc_battery_text, Color.parseColor("#EF4444"))
                    }
                }
            } else {
                views.setTextViewText(R.id.anc_battery_text, "💤 Standby")
                views.setInt(R.id.anc_btn_refresh_battery, "setBackgroundResource", R.drawable.widget_battery_badge_dim)
                views.setTextColor(R.id.anc_battery_text, Color.parseColor("#8B949E"))
            }

            // 4. Buttons State & Click Handlers
            val isAnc = status.mode == AncManager.MODE_ANC
            val isNormal = status.mode == AncManager.MODE_NORMAL
            val isTransp = status.mode == AncManager.MODE_TRANSPARENCY

            // ANC Button
            views.setInt(
                R.id.btn_mode_anc,
                "setBackgroundResource",
                if (isAnc) R.drawable.btn_anc_active else R.drawable.btn_anc_inactive
            )
            views.setTextColor(
                R.id.text_anc_label,
                if (isAnc) Color.parseColor("#4EBA6F") else Color.parseColor("#8B949E")
            )
            val ancIntent = Intent(context, AncWidgetProvider::class.java).apply {
                action = ACTION_SET_MODE
                putExtra(EXTRA_MODE, AncManager.MODE_ANC)
            }
            views.setOnClickPendingIntent(
                R.id.btn_mode_anc,
                PendingIntent.getBroadcast(
                    context, 201, ancIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            // Normal Button
            views.setInt(
                R.id.btn_mode_normal,
                "setBackgroundResource",
                if (isNormal) R.drawable.btn_normal_active else R.drawable.btn_anc_inactive
            )
            views.setTextColor(
                R.id.text_normal_label,
                if (isNormal) Color.parseColor("#FFFFFF") else Color.parseColor("#8B949E")
            )
            val normalIntent = Intent(context, AncWidgetProvider::class.java).apply {
                action = ACTION_SET_MODE
                putExtra(EXTRA_MODE, AncManager.MODE_NORMAL)
            }
            views.setOnClickPendingIntent(
                R.id.btn_mode_normal,
                PendingIntent.getBroadcast(
                    context, 202, normalIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            // Transparency Button
            views.setInt(
                R.id.btn_mode_transparency,
                "setBackgroundResource",
                if (isTransp) R.drawable.btn_transparency_active else R.drawable.btn_anc_inactive
            )
            views.setTextColor(
                R.id.text_transparency_label,
                if (isTransp) Color.parseColor("#60A5FA") else Color.parseColor("#8B949E")
            )
            val transpIntent = Intent(context, AncWidgetProvider::class.java).apply {
                action = ACTION_SET_MODE
                putExtra(EXTRA_MODE, AncManager.MODE_TRANSPARENCY)
            }
            views.setOnClickPendingIntent(
                R.id.btn_mode_transparency,
                PendingIntent.getBroadcast(
                    context, 203, transpIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

