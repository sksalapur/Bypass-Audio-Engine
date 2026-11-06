package com.music.bypass.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bypass.data.TelemetryResponse
import com.music.bypass.ui.theme.*

@Composable
fun SignalChainRail(telemetry: TelemetryResponse?) {
    val chain = telemetry?.signalChain
    val n1 = chain?.node1Source
    val n2 = chain?.node2Decoder
    val n3 = chain?.node3Resampler
    val n4 = chain?.node4Dsp
    val n5 = chain?.node5Endpoint

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
        Text(
            text = "HARDWARE AUDIO SIGNAL CHAIN",
            style = Typography.labelSmall,
            color = GoldAccent
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Node 1: Source
        val rateStr = if ((n1?.sampleRate ?: 0) > 0) "%.1f kHz".format((n1?.sampleRate ?: 44100) / 1000.0) else "44.1 kHz"
        val fmtStr = "${n1?.container ?: "FLAC"} • $rateStr / ${n1?.bitDepth ?: 16}-bit"
        val n1Subtitle = if (n1?.artist.isNullOrBlank() || n1?.artist == "BitChord") fmtStr else "${n1?.artist} • $fmtStr"

        RailNode(
            index = "01",
            title = n1?.title ?: "BitChord Track",
            subtitle = n1Subtitle,
            badge = n1?.badge ?: "BIT-EXACT",
            badgeColor = GoldAccent,
            isLast = false
        )

        // Node 2: Decoder
        RailNode(
            index = "02",
            title = n2?.engine ?: "c2.android.flac.decoder / FFmpeg",
            subtitle = n2?.mode ?: "AudioTrack Float32",
            badge = n2?.badge ?: "LOSSLESS",
            badgeColor = SageGreen,
            isLast = false
        )

        // Node 3: Resampler
        RailNode(
            index = "03",
            title = n3?.status ?: "Bypassed (1:1 Bit-Exact)",
            subtitle = "Resampling Ratio: ${n3?.ratio ?: "1:1"}",
            badge = n3?.badge ?: if (isUsb) "BYPASS ACTIVE" else "MIXER ACTIVE",
            badgeColor = if (isUsb) SageGreen else AmberAccent,
            isLast = false
        )

        // Node 4: DSP
        RailNode(
            index = "04",
            title = n4?.status ?: "Bit-Perfect Direct Pass (All FX Bypassed)",
            subtitle = "Volume Truncation: ${n4?.volumeTruncation ?: "Zero Loss"}",
            badge = n4?.badge ?: "BIT-PERFECT",
            badgeColor = if (isUsb) GoldAccent else BlueAccent,
            isLast = false
        )

        // Node 5: Endpoint
        val isHiResBt = isBt && (telemetry?.bluetooth?.codec?.contains("LHDC", ignoreCase = true) == true ||
                telemetry?.bluetooth?.codec?.contains("LDAC", ignoreCase = true) == true ||
                telemetry?.bluetooth?.codec?.contains("aptX HD", ignoreCase = true) == true)
        val defaultBtChip = if (telemetry?.bluetooth?.codec != null) "${telemetry.bluetooth.codec} Encoder" else "Bluetooth Encoder"
        val n5Subtitle = if (isBt) {
            "${n5?.chip ?: defaultBtChip} • ${n5?.format ?: if (isHiResBt) "96.0 kHz • 24-bit" else "44.1 kHz • 16-bit"}"
        } else {
            "${n5?.chip ?: "DAC"} • ${n5?.format ?: "S24_3LE"} • ${n5?.hardwareRate ?: "Standby"}"
        }

        RailNode(
            index = "05",
            title = n5?.deviceName ?: if (isUsb) "Moondrop Chu II DSP" else if (isBt) (telemetry?.bluetooth?.deviceName ?: "Bluetooth Headset") else "System Audio",
            subtitle = n5Subtitle,
            badge = n5?.badge ?: if (isUsb) "UAC2 DIRECT" else if (isHiResBt) "HI-RES WIRELESS" else if (isBt) "BT A2DP" else "HAL DIRECT",
            badgeColor = if (isUsb) GoldAccent else if (isHiResBt) BlueAccent else if (isBt) SageGreen else TextDim,
            isLast = true
        )
    }
}

@Composable
private fun RailNode(
    index: String,
    title: String,
    subtitle: String,
    badge: String,
    badgeColor: Color,
    isLast: Boolean
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        // Vertical indicator track
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(28.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(SurfaceElevated)
                    .border(1.dp, badgeColor.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = index,
                    fontSize = 9.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor
                )
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .height(38.dp)
                        .background(SurfaceBorder)
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Content
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = Typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .border(0.5.dp, badgeColor.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badge,
                        style = Typography.labelSmall,
                        fontSize = 8.sp,
                        color = badgeColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = Typography.bodySmall,
                color = TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

