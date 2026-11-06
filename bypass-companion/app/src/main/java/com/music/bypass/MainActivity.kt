package com.music.bypass

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bypass.data.BypassDaemonClient
import com.music.bypass.data.ControlsConfig
import com.music.bypass.data.TelemetryResponse
import com.music.bypass.ui.components.*
import com.music.bypass.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        optimizeDisplayRefreshRate()
        com.music.bypass.service.BudsService.start(this)

        setContent {
            BypassTheme {
                val coroutineScope = rememberCoroutineScope()
                var telemetry by remember { mutableStateOf<TelemetryResponse?>(null) }
                var config by remember { mutableStateOf<ControlsConfig?>(null) }
                var isRestarting by remember { mutableStateOf(false) }
                var activeAction by remember { mutableStateOf<String?>(null) }

                // Polling Loop: Telemetry (1200ms) with change guard to avoid needless recomposition
                LaunchedEffect(Unit) {
                    while (isActive) {
                        BypassDaemonClient.getTelemetry()?.let { newTel ->
                            if (newTel != telemetry) {
                                telemetry = newTel
                                com.music.bypass.widget.SignalChainWidgetProvider.notifyUpdate(this@MainActivity)
                            }
                        }
                        delay(1200)
                    }
                }

                // Polling Loop: Controls (4000ms)
                LaunchedEffect(Unit) {
                    while (isActive) {
                        BypassDaemonClient.getControls()?.let { newCfg ->
                            if (newCfg != config && activeAction == null) {
                                config = newCfg
                            }
                        }
                        delay(4000)
                    }
                }

                // Helper to broadcast setting change directly to hooked BitChord
                fun broadcastToBitChord(key: String, value: String, reload: Boolean = true) {
                    val intent = Intent("com.music.bypass.ACTION_SET_CONFIG").apply {
                        putExtra("key", key)
                        putExtra("value", value)
                        putExtra("reload", reload)
                    }
                    sendBroadcast(intent)
                }

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BgDark)
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(BgDark)
                            .padding(innerPadding)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 40.dp)
                        ) {
                            TopHeader(telemetry = telemetry)

                            SignalChainRail(telemetry = telemetry)

                            BluetoothCard(telemetry = telemetry)

                            ControlsCard(
                                config = config,
                                onToggleControl = { key, value ->
                                    // 1. Optimistic instant local state update
                                    config = when (key) {
                                        "direct_bit_perfect_mode" -> config?.copy(directBitPerfectMode = value)
                                        "usb_hardware_volume" -> config?.copy(usbHardwareVolume = value)
                                        "internal_direct_output" -> config?.copy(internalDirectOutput = value)
                                        "bit_perfect_rate_switching" -> config?.copy(bitPerfectRateSwitching = value)
                                        else -> config
                                    }

                                    val label = when (key) {
                                        "direct_bit_perfect_mode" -> if (value) "Enabling USB Exclusive..." else "Releasing USB Driver..."
                                        "bit_perfect_rate_switching" -> if (value) "Enabling Bit-Perfect Clock..." else "Locking Sample Clock..."
                                        "internal_direct_output" -> if (value) "Routing Hi-Res Direct Jack..." else "Restoring Standard Jack..."
                                        "usb_hardware_volume" -> if (value) "Enabling DAC Hardware Volume..." else "Bypassing Hardware Attenuation..."
                                        else -> "Applying Setting..."
                                    }
                                    activeAction = label

                                    // 2. Direct broadcast to live BitChord in RAM (causes authentic reload disruption)
                                    broadcastToBitChord(key, value.toString(), reload = true)

                                    // 3. Background asynchronous persistence via root daemon
                                    coroutineScope.launch {
                                        BypassDaemonClient.setControl(key, value.toString())
                                        delay(800)
                                        activeAction = null
                                    }
                                },
                                onSetVolume = { vol ->
                                    config = config?.copy(hardwareVolumeLevel = vol)
                                    activeAction = "Setting DAC Register: $vol%"
                                    broadcastToBitChord("hardware_volume_level", vol.toString(), reload = false)

                                    coroutineScope.launch {
                                        BypassDaemonClient.setVolume(vol)
                                        delay(600)
                                        activeAction = null
                                    }
                                },
                                onSetNormalization = { mode ->
                                    config = config?.copy(flacReplayGainMode = mode)
                                    activeAction = "Reconfiguring FLAC Normalizer..."
                                    broadcastToBitChord("flac_replay_gain_mode", mode, reload = true)

                                    coroutineScope.launch {
                                        BypassDaemonClient.setControl("flac_replay_gain_mode", mode)
                                        delay(800)
                                        activeAction = null
                                    }
                                },
                                onSetBuffer = { buf ->
                                    config = config?.copy(audioBufferSizing = buf)
                                    activeAction = "Tuning ALSA Ring Buffer..."
                                    broadcastToBitChord("audio_buffer_sizing", buf, reload = true)

                                    coroutineScope.launch {
                                        BypassDaemonClient.setControl("audio_buffer_sizing", buf)
                                        delay(800)
                                        activeAction = null
                                    }
                                },
                                onRestartAudio = {
                                    coroutineScope.launch {
                                        isRestarting = true
                                        activeAction = "Restarting Audio Subsystem..."
                                        val ok = BypassDaemonClient.restartAudioSubsystem()
                                        Toast.makeText(
                                            this@MainActivity,
                                            if (ok) "Audio subsystem restarted successfully" else "Command dispatched to audio daemon",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        delay(2400)
                                        isRestarting = false
                                        activeAction = null
                                        telemetry = BypassDaemonClient.getTelemetry()
                                        config = BypassDaemonClient.getControls()
                                    }
                                },
                                isRestarting = isRestarting
                            )

                            DiagnosticsCard(telemetry = telemetry)
                        }

                        // Floating action indicator banner for instant visual feedback
                        AnimatedVisibility(
                            visible = activeAction != null,
                            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 16.dp)
                        ) {
                            activeAction?.let { actionText ->
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(SurfaceCard.copy(alpha = 0.95f))
                                        .border(1.dp, GoldAccent.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = GoldAccent
                                    )
                                    Text(
                                        text = actionText,
                                        style = Typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = GoldAccent,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Forces window to request high refresh rate (90 Hz) on supported panels.
     */
    private fun optimizeDisplayRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val modes = display?.supportedModes
                val mode90 = modes?.firstOrNull { it.refreshRate >= 89.0f } ?: modes?.maxByOrNull { it.refreshRate }
                if (mode90 != null) {
                    val params = window.attributes
                    params.preferredDisplayModeId = mode90.modeId
                    window.attributes = params
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val modes = windowManager.defaultDisplay.supportedModes
                val mode90 = modes?.firstOrNull { it.refreshRate >= 89.0f } ?: modes?.maxByOrNull { it.refreshRate }
                if (mode90 != null) {
                    val params = window.attributes
                    params.preferredDisplayModeId = mode90.modeId
                    window.attributes = params
                }
            }
        } catch (_: Exception) {}
    }
}
