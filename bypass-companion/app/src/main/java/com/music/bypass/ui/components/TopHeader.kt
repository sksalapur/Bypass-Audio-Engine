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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bypass.data.TelemetryResponse
import com.music.bypass.ui.theme.*

@Composable
fun TopHeader(telemetry: TelemetryResponse?) {
    val isUsb = telemetry?.isUsbActive == true
    val isBt = telemetry?.activeEndpoint == "BLUETOOTH"
    val isPlaying = telemetry?.playbackState == "PLAYING"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // App Title & Status Pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "BYPASS",
                        style = Typography.headlineLarge,
                        color = GoldAccent
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "AUDIOPHILE ENGINE",
                        style = Typography.labelSmall,
                        color = TextDim,
                        letterSpacing = 1.sp
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(
                    text = when {
                        isUsb -> if (isPlaying) "HAL DIRECT" else "HAL READY"
                        isBt -> "A2DP ACTIVE"
                        else -> "SYSTEM HAL"
                    },
                    color = when {
                        isUsb -> GoldAccent
                        isBt -> BlueAccent
                        else -> TextDim
                    }
                )
                StatusPill(
                    text = when {
                        isUsb -> "AF BYPASS"
                        isBt -> "MIXER STREAM"
                        else -> "AF MIXER"
                    },
                    color = when {
                        isUsb -> SageGreen
                        isBt -> AmberAccent
                        else -> TextDim
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3-Box Stream Monitor Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceCard)
                .border(1.dp, SurfaceBorder, RoundedCornerShape(8.dp))
                .padding(vertical = 10.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MonitorItem(
                caption = "SOURCE",
                value = telemetry?.topDisplay?.rateText ?: "Standby"
            )
            Text(text = "➔", color = TextDim, fontSize = 12.sp)
            MonitorItem(
                caption = "PROCESSING",
                value = if (isUsb) "Direct Pass" else if (isBt) "A2DP Stream" else "Standby"
            )
            Text(text = "➔", color = TextDim, fontSize = 12.sp)
            MonitorItem(
                caption = "ENDPOINT",
                value = telemetry?.topDisplay?.endpointText ?: "Standby • 24-bit"
            )
        }
    }
}

@Composable
private fun StatusPill(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = Typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun MonitorItem(caption: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = caption, style = Typography.labelSmall, color = TextDim)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = Typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

