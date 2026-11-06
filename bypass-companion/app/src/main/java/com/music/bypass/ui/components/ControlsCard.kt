package com.music.bypass.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bypass.data.ControlsConfig
import com.music.bypass.ui.theme.*

@Composable
fun ControlsCard(
    config: ControlsConfig?,
    onToggleControl: (String, Boolean) -> Unit,
    onSetVolume: (Int) -> Unit,
    onSetNormalization: (String) -> Unit,
    onSetBuffer: (String) -> Unit,
    onRestartAudio: () -> Unit,
    isRestarting: Boolean
) {
    var localVol by remember(config?.hardwareVolumeLevel) {
        mutableFloatStateOf((config?.hardwareVolumeLevel ?: 100).toFloat())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Card 1: Direct HAL & Output Streams
        ControlSectionBox(title = "DIRECT HAL & OUTPUT STREAM") {
            ToggleRow(
                title = "USB Exclusive Output (usbdevfs)",
                desc = "Direct hardware communication via kernel USB endpoints",
                checked = config?.directBitPerfectMode ?: true,
                onCheckedChange = { onToggleControl("direct_bit_perfect_mode", it) }
            )
            DividerLine()
            ToggleRow(
                title = "Bit-Perfect Rate Switching",
                desc = "Reconfigure hardware clock dynamically (44.1 - 192 kHz)",
                checked = config?.bitPerfectRateSwitching ?: true,
                onCheckedChange = { onToggleControl("bit_perfect_rate_switching", it) }
            )
            DividerLine()
            ToggleRow(
                title = "Internal Hi-Res Direct Output",
                desc = "Vendor direct PCM route on internal 3.5mm jack",
                checked = config?.internalDirectOutput ?: true,
                onCheckedChange = { onToggleControl("internal_direct_output", it) }
            )
        }

        // Card 2: Hardware Volume Control
        ControlSectionBox(title = "DAC HARDWARE VOLUME & ATTENUATION") {
            ToggleRow(
                title = "DAC Hardware Volume Control",
                desc = "Control DAC registers directly instead of truncating PCM bits",
                checked = config?.usbHardwareVolume ?: true,
                onCheckedChange = { onToggleControl("usb_hardware_volume", it) }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "UAC2 PCM Register Level", style = Typography.bodySmall, color = TextMuted)
                Text(
                    text = "${localVol.toInt()}%",
                    style = Typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = GoldAccent
                )
            }

            Slider(
                value = localVol,
                onValueChange = { localVol = it },
                onValueChangeFinished = { onSetVolume(localVol.toInt()) },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = GoldAccent,
                    activeTrackColor = GoldAccent,
                    inactiveTrackColor = SurfaceBorder
                )
            )
        }

        // Card 3: Normalization & Audio Buffer
        ControlSectionBox(title = "FIDELITY & BUFFER TUNING") {
            Text(text = "FLAC Normalization Mode", style = Typography.bodySmall, color = TextDim)
            Spacer(modifier = Modifier.height(6.dp))
            SegmentedButtons(
                options = listOf(
                    "BIT_PERFECT_OFF" to "Bit-Perfect",
                    "REPLAY_GAIN_TRACK" to "ReplayGain",
                    "DYNAMIC_NORMALIZATION" to "Dynamic"
                ),
                selected = config?.flacReplayGainMode ?: "BIT_PERFECT_OFF",
                onSelect = onSetNormalization
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(text = "Audio Buffer Sizing", style = Typography.bodySmall, color = TextDim)
            Spacer(modifier = Modifier.height(6.dp))
            SegmentedButtons(
                options = listOf(
                    "LOW_LATENCY" to "Low (250ms)",
                    "STANDARD" to "Standard",
                    "AUDIOPHILE_STABLE" to "Stable (1.5s)"
                ),
                selected = config?.audioBufferSizing ?: "AUDIOPHILE_STABLE",
                onSelect = onSetBuffer
            )
        }

        // Card 4: System Operations
        ControlSectionBox(title = "SYSTEM SERVICE OPERATIONS") {
            Text(
                text = "Kills audioserver and restarts HAL daemon to unlock stuck USB devices and apply policy overrides.",
                style = Typography.bodySmall,
                color = TextMuted
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onRestartAudio,
                enabled = !isRestarting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceElevated,
                    contentColor = TextPrimary
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder)
            ) {
                if (isRestarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = GoldAccent
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Restarting Subsystem...", style = Typography.titleMedium, color = GoldAccent)
                } else {
                    Text(text = "Restart Audio Server", style = Typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ControlSectionBox(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard)
            .border(1.dp, SurfaceBorder, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(text = title, style = Typography.labelSmall, color = GoldAccent)
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun ToggleRow(title: String, desc: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = Typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = desc, style = Typography.bodySmall, color = TextMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = GoldAccent,
                checkedTrackColor = GoldAccent.copy(alpha = 0.35f),
                uncheckedThumbColor = TextDim,
                uncheckedTrackColor = SurfaceElevated
            )
        )
    }
}

@Composable
private fun SegmentedButtons(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceElevated)
            .border(1.dp, SurfaceBorder, RoundedCornerShape(6.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { (key, label) ->
            val isSelected = selected == key
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isSelected) GoldAccent.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { onSelect(key) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) GoldAccent else TextMuted
                )
            }
        }
    }
}

@Composable
private fun DividerLine() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 10.dp),
        thickness = 0.5.dp,
        color = SurfaceBorder
    )
}

