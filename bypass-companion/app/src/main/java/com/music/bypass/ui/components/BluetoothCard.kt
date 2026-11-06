package com.music.bypass.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bypass.data.AncManager
import com.music.bypass.data.TelemetryResponse
import com.music.bypass.ui.theme.*
import com.music.bypass.widget.AncWidgetProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun BluetoothCard(telemetry: TelemetryResponse?) {
    val bt = telemetry?.bluetooth
    if (telemetry?.bluetoothSuppressed == true || telemetry?.isUsbActive == true || bt == null) {
        return
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var activeMode by remember { mutableStateOf(AncManager.getActiveMode(context)) }
    var batteryLevel by remember { mutableIntStateOf(AncManager.getCachedBattery(context)) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                if (intent.action == AncWidgetProvider.ACTION_SET_MODE || intent.action == AncWidgetProvider.ACTION_WIDGET_UPDATE) {
                    val newMode = intent.getStringExtra(AncWidgetProvider.EXTRA_MODE)
                        ?: (ctx?.let { AncManager.getActiveMode(it) } ?: activeMode)
                    activeMode = newMode
                    ctx?.let {
                        val b = AncManager.getCachedBattery(it)
                        if (b >= 0) batteryLevel = b
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AncWidgetProvider.ACTION_SET_MODE)
            addAction(AncWidgetProvider.ACTION_WIDGET_UPDATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        val status = AncManager.getBudsStatus(context)
        activeMode = status.mode
        batteryLevel = status.battery

        while (isActive) {
            delay(500)
            val curMode = AncManager.getActiveMode(context)
            if (activeMode != curMode) {
                activeMode = curMode
            }
            val curBatt = AncManager.getCachedBattery(context)
            if (curBatt >= 0 && batteryLevel != curBatt) {
                batteryLevel = curBatt
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard)
            .border(1.dp, BlueAccent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ACTIVE BLUETOOTH SINK",
                        style = Typography.labelSmall,
                        color = BlueAccent
                    )
                    if (batteryLevel >= 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        batteryLevel >= 40 -> SageGreen.copy(alpha = 0.15f)
                                        batteryLevel >= 20 -> AmberAccent.copy(alpha = 0.15f)
                                        else -> RustRed.copy(alpha = 0.15f)
                                    }
                                )
                                .border(
                                    1.dp,
                                    when {
                                        batteryLevel >= 40 -> SageGreen.copy(alpha = 0.4f)
                                        batteryLevel >= 20 -> AmberAccent.copy(alpha = 0.4f)
                                        else -> RustRed.copy(alpha = 0.4f)
                                    },
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "🔋 $batteryLevel%",
                                style = Typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    batteryLevel >= 40 -> SageGreen
                                    batteryLevel >= 20 -> AmberAccent
                                    else -> RustRed
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = bt.deviceName,
                    style = Typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            val displayCodec = when {
                bt.codec.contains("LHDC_V3", ignoreCase = true) ||
                bt.codec.contains("LHDC Version 5", ignoreCase = true) ||
                bt.codec.equals("LHDC", ignoreCase = true) -> "LHDC Version 5"
                bt.codec.contains("LHDC_V2", ignoreCase = true) -> "LHDC Version 2"
                else -> bt.codec
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(BlueAccent.copy(alpha = 0.15f))
                    .border(1.dp, BlueAccent.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = displayCodec,
                    style = Typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BlueAccent
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Grid Specs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceElevated)
                .padding(vertical = 8.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BtSpec("CLOCK", "%.1f kHz".format(bt.sampleRate / 1000.0))
            BtSpec("DEPTH", "${bt.bitDepth}-bit")
            BtSpec("BITRATE", bt.bitrate)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Quick ANC Mode Toggle Buttons (Horizontal Trio)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AncModeButton(
                modifier = Modifier.weight(1f),
                label = "ANC",
                icon = "🛡️",
                isActive = activeMode == AncManager.MODE_ANC,
                activeColor = SageGreen,
                onClick = {
                    activeMode = AncManager.MODE_ANC
                    AncManager.setActiveMode(context, AncManager.MODE_ANC)
                    AncWidgetProvider.notifyUpdate(context)
                    coroutineScope.launch {
                        AncManager.switchMode(context, AncManager.MODE_ANC)
                        AncWidgetProvider.notifyUpdate(context)
                    }
                }
            )

            AncModeButton(
                modifier = Modifier.weight(1f),
                label = "Normal",
                icon = "⚪",
                isActive = activeMode == AncManager.MODE_NORMAL,
                activeColor = Color.White,
                onClick = {
                    activeMode = AncManager.MODE_NORMAL
                    AncManager.setActiveMode(context, AncManager.MODE_NORMAL)
                    AncWidgetProvider.notifyUpdate(context)
                    coroutineScope.launch {
                        AncManager.switchMode(context, AncManager.MODE_NORMAL)
                        AncWidgetProvider.notifyUpdate(context)
                    }
                }
            )

            AncModeButton(
                modifier = Modifier.weight(1f),
                label = "Ambient",
                icon = "🔊",
                isActive = activeMode == AncManager.MODE_TRANSPARENCY,
                activeColor = BlueAccent,
                onClick = {
                    activeMode = AncManager.MODE_TRANSPARENCY
                    AncManager.setActiveMode(context, AncManager.MODE_TRANSPARENCY)
                    AncWidgetProvider.notifyUpdate(context)
                    coroutineScope.launch {
                        AncManager.switchMode(context, AncManager.MODE_TRANSPARENCY)
                        AncWidgetProvider.notifyUpdate(context)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Honest Verdict Notice
        Text(
            text = bt.verdict,
            style = Typography.bodySmall,
            color = AmberAccent,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun AncModeButton(
    modifier: Modifier = Modifier,
    label: String,
    icon: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isActive) activeColor.copy(alpha = 0.15f) else SurfaceElevated)
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) activeColor else SurfaceBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = icon, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = Typography.labelSmall,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            color = if (isActive) activeColor else TextDim
        )
    }
}

@Composable
private fun BtSpec(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = Typography.labelSmall, color = TextDim)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, style = Typography.bodySmall, fontWeight = FontWeight.Bold, color = TextPrimary)
    }
}
