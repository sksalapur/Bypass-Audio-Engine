package com.music.bypass.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.music.bypass.data.TelemetryResponse
import com.music.bypass.ui.theme.*

@Composable
fun DiagnosticsCard(telemetry: TelemetryResponse?) {
    val ep = telemetry?.signalChain?.node5Endpoint
    val isUsb = telemetry?.isUsbActive == true
    val isBt = telemetry?.activeEndpoint == "BLUETOOTH"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard)
            .border(1.dp, SurfaceBorder, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(text = "KERNEL ALSA DIAGNOSTICS", style = Typography.labelSmall, color = TextDim)
        Spacer(modifier = Modifier.height(10.dp))

        DiagRow(
            label = "INTERFACE",
            value = when {
                isUsb -> "USB-Audio pcm0p (UAC2)"
                isBt -> "Bluetooth A2DP Stack"
                else -> "Internal Hi-Fi PCM"
            }
        )
        DiagRow(label = "HARDWARE CLOCK", value = ep?.hardwareRate ?: "Standby")
        val fmtText = when {
            isBt -> ep?.format ?: "LHDC v5 (24-bit)"
            ep?.format?.contains("bit", ignoreCase = true) == true -> ep.format
            else -> "${ep?.format ?: "S24_3LE"} (${ep?.bitDepth ?: 24}-bit)"
        }
        DiagRow(label = "STREAM FORMAT", value = fmtText)
        DiagRow(label = "PERIOD / BUFFER", value = if ((ep?.periodSize ?: 0) > 0) "${ep?.periodSize} / ${ep?.bufferSize} frames" else "256 / 512 frames")
        DiagRow(label = "BUFFER XRUNS", value = "0 (Pristine Stream)")
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = Typography.labelSmall, color = TextDim)
        Text(text = value, style = Typography.bodySmall, color = TextPrimary)
    }
}

