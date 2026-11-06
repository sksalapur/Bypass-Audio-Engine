package com.music.bypass.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.music.bypass.MainActivity
import com.music.bypass.R
import com.music.bypass.data.BypassDaemonClient
import com.music.bypass.data.TelemetryResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SignalChainWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                val telemetry = BypassDaemonClient.getTelemetry()
                for (appWidgetId in appWidgetIds) {
                    updateWidget(context, appWidgetManager, appWidgetId, telemetry)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH || intent.action == ACTION_WIDGET_UPDATE) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, SignalChainWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            if (allWidgetIds.isNotEmpty()) {
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        val telemetry = BypassDaemonClient.getTelemetry()
                        for (widgetId in allWidgetIds) {
                            updateWidget(context, appWidgetManager, widgetId, telemetry)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.music.bypass.ACTION_WIDGET_REFRESH"
        const val ACTION_WIDGET_UPDATE = "com.music.bypass.WIDGET_UPDATE"

        fun notifyUpdate(context: Context) {
            val intent = Intent(context, SignalChainWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_UPDATE
            }
            context.sendBroadcast(intent)
        }

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            telemetry: TelemetryResponse?
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_signal_chain)

            // Click header title or track info to launch MainActivity (NOT entire root)
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingLaunch = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_header_title, pendingLaunch)
            views.setOnClickPendingIntent(R.id.node1_title, pendingLaunch)
            views.setOnClickPendingIntent(R.id.node1_subtitle, pendingLaunch)

            // Click refresh icon to reload telemetry (with dedicated touch area)
            val refreshIntent = Intent(context, SignalChainWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            val pendingRefresh = PendingIntent.getBroadcast(
                context, 1, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_refresh, pendingRefresh)

            val chain = telemetry?.signalChain
            val n1 = chain?.node1Source
            val n2 = chain?.node2Decoder
            val n3 = chain?.node3Resampler
            val n4 = chain?.node4Dsp
            val n5 = chain?.node5Endpoint

            val isUsb = telemetry?.isUsbActive == true
            val isBt = telemetry?.activeEndpoint == "BLUETOOTH"

            // Node 1: Source
            val n1Title = n1?.title ?: "BitChord Track"
            val rateStr = if ((n1?.sampleRate ?: 0) > 0) "%.1f kHz".format((n1?.sampleRate ?: 44100) / 1000.0) else "44.1 kHz"
            val fmtStr = "${n1?.container ?: "FLAC"} • $rateStr / ${n1?.bitDepth ?: 16}-bit"
            val n1Subtitle = if (n1?.artist.isNullOrBlank() || n1?.artist == "BitChord") fmtStr else "${n1?.artist} • $fmtStr"
            val n1Badge = n1?.badge ?: if (telemetry?.playbackState == "PLAYING") "ACTIVE STREAM" else "STANDBY"

            views.setTextViewText(R.id.node1_title, n1Title)
            views.setTextViewText(R.id.node1_subtitle, n1Subtitle)
            views.setTextViewText(R.id.node1_badge, n1Badge)
            views.setInt(R.id.node1_badge, "setBackgroundResource", R.drawable.widget_badge_gold)
            views.setTextColor(R.id.node1_badge, Color.parseColor("#D4AF37"))
            views.setInt(R.id.node1_index, "setBackgroundResource", R.drawable.widget_node_gold)
            views.setTextColor(R.id.node1_index, Color.parseColor("#D4AF37"))

            // Node 2: Decoder
            val n2Title = n2?.engine ?: "c2.android.flac.decoder / FFmpeg"
            val n2Subtitle = n2?.mode ?: "AudioTrack Float32"
            val n2Badge = n2?.badge ?: "LOSSLESS"

            views.setTextViewText(R.id.node2_title, n2Title)
            views.setTextViewText(R.id.node2_subtitle, n2Subtitle)
            views.setTextViewText(R.id.node2_badge, n2Badge)
            views.setInt(R.id.node2_badge, "setBackgroundResource", R.drawable.widget_badge_green)
            views.setTextColor(R.id.node2_badge, Color.parseColor("#4EBA6F"))
            views.setInt(R.id.node2_index, "setBackgroundResource", R.drawable.widget_node_green)
            views.setTextColor(R.id.node2_index, Color.parseColor("#4EBA6F"))

            // Node 3: Resampler
            val n3Title = n3?.status ?: if (isUsb) "Bypassed (1:1 Bit-Exact)" else "AudioFlinger Resampler"
            val n3Subtitle = "Resampling Ratio: ${n3?.ratio ?: "1:1"}"
            val n3Badge = n3?.badge ?: if (isUsb) "BYPASS ACTIVE" else "MIXER ACTIVE"

            views.setTextViewText(R.id.node3_title, n3Title)
            views.setTextViewText(R.id.node3_subtitle, n3Subtitle)
            views.setTextViewText(R.id.node3_badge, n3Badge)
            val n3Color = if (isUsb) "#4EBA6F" else "#F59E0B"
            val n3BadgeBg = if (isUsb) R.drawable.widget_badge_green else R.drawable.widget_badge_amber
            val n3NodeBg = if (isUsb) R.drawable.widget_node_green else R.drawable.widget_node_amber
            views.setInt(R.id.node3_badge, "setBackgroundResource", n3BadgeBg)
            views.setTextColor(R.id.node3_badge, Color.parseColor(n3Color))
            views.setInt(R.id.node3_index, "setBackgroundResource", n3NodeBg)
            views.setTextColor(R.id.node3_index, Color.parseColor(n3Color))

            // Node 4: DSP
            val n4Title = n4?.status ?: if (isUsb) "Bit-Perfect Direct Pass (All FX Bypassed)" else "System Audio Pipeline"
            val n4Subtitle = "Volume Truncation: ${n4?.volumeTruncation ?: if (isUsb) "Zero Loss" else "Software Scaled"}"
            val n4Badge = n4?.badge ?: if (isUsb) "BIT-PERFECT" else "A2DP STACK"

            views.setTextViewText(R.id.node4_title, n4Title)
            views.setTextViewText(R.id.node4_subtitle, n4Subtitle)
            views.setTextViewText(R.id.node4_badge, n4Badge)
            val n4Color = if (isUsb) "#D4AF37" else "#60A5FA"
            val n4BadgeBg = if (isUsb) R.drawable.widget_badge_gold else R.drawable.widget_badge_blue
            val n4NodeBg = if (isUsb) R.drawable.widget_node_gold else R.drawable.widget_node_blue
            views.setInt(R.id.node4_badge, "setBackgroundResource", n4BadgeBg)
            views.setTextColor(R.id.node4_badge, Color.parseColor(n4Color))
            views.setInt(R.id.node4_index, "setBackgroundResource", n4NodeBg)
            views.setTextColor(R.id.node4_index, Color.parseColor(n4Color))

            // Node 5: Endpoint
            val isHiResBt = isBt && (telemetry?.bluetooth?.codec?.contains("LHDC", ignoreCase = true) == true ||
                    telemetry?.bluetooth?.codec?.contains("LDAC", ignoreCase = true) == true ||
                    telemetry?.bluetooth?.codec?.contains("aptX HD", ignoreCase = true) == true)
            val defaultBtChip = if (telemetry?.bluetooth?.codec != null) "${telemetry.bluetooth.codec} Encoder" else "Bluetooth Encoder"
            val n5Title = n5?.deviceName ?: if (isUsb) "Moondrop Chu II DSP" else if (isBt) (telemetry?.bluetooth?.deviceName ?: "realme Buds T500 Pro") else "System Audio"
            val n5Subtitle = if (isBt) {
                "${n5?.chip ?: defaultBtChip} • ${n5?.format ?: if (isHiResBt) "96.0 kHz • 24-bit" else "44.1 kHz • 16-bit"}"
            } else {
                "${n5?.chip ?: "DAC"} • ${n5?.format ?: "S24_3LE"} • ${n5?.hardwareRate ?: "Standby"}"
            }
            val n5Badge = n5?.badge ?: if (isUsb) "UAC2 DIRECT" else if (isHiResBt) "HI-RES WIRELESS" else if (isBt) "BT A2DP" else "HAL READY"

            views.setTextViewText(R.id.node5_title, n5Title)
            views.setTextViewText(R.id.node5_subtitle, n5Subtitle)
            views.setTextViewText(R.id.node5_badge, n5Badge)
            val n5Color = if (isUsb) "#D4AF37" else if (isHiResBt) "#60A5FA" else if (isBt) "#4EBA6F" else "#8B949E"
            val n5BadgeBg = if (isUsb) R.drawable.widget_badge_gold else if (isHiResBt) R.drawable.widget_badge_blue else R.drawable.widget_badge_dim
            val n5NodeBg = if (isUsb) R.drawable.widget_node_gold else if (isHiResBt) R.drawable.widget_node_blue else R.drawable.widget_node_dim
            views.setInt(R.id.node5_badge, "setBackgroundResource", n5BadgeBg)
            views.setTextColor(R.id.node5_badge, Color.parseColor(n5Color))
            views.setInt(R.id.node5_index, "setBackgroundResource", n5NodeBg)
            views.setTextColor(R.id.node5_index, Color.parseColor(n5Color))

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

