package com.music.bypass.data

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

@Immutable
data class TelemetryResponse(
    @SerializedName("playback_state") val playbackState: String = "STANDBY",
    @SerializedName("active_endpoint") val activeEndpoint: String = "NONE",
    @SerializedName("active_package") val activePackage: String? = null,
    @SerializedName("is_video_app") val isVideoApp: Boolean = false,
    @SerializedName("is_usb_active") val isUsbActive: Boolean = false,
    @SerializedName("is_internal_active") val isInternalActive: Boolean = false,
    @SerializedName("bluetooth_suppressed") val bluetoothSuppressed: Boolean = true,
    @SerializedName("top_display") val topDisplay: TopDisplay = TopDisplay(),
    @SerializedName("signal_chain") val signalChain: SignalChain = SignalChain(),
    @SerializedName("bluetooth") val bluetooth: BluetoothTelemetry? = null
)

@Immutable
data class TopDisplay(
    @SerializedName("rate_text") val rateText: String = "Standby",
    @SerializedName("endpoint_text") val endpointText: String = "Standby • 24b"
)

@Immutable
data class SignalChain(
    @SerializedName("node1_source") val node1Source: Node1Source = Node1Source(),
    @SerializedName("node2_decoder") val node2Decoder: Node2Decoder = Node2Decoder(),
    @SerializedName("node3_resampler") val node3Resampler: Node3Resampler = Node3Resampler(),
    @SerializedName("node4_dsp") val node4Dsp: Node4Dsp = Node4Dsp(),
    @SerializedName("node5_endpoint") val node5Endpoint: Node5Endpoint = Node5Endpoint()
)

@Immutable
data class Node1Source(
    val app: String = "BitChord (com.music.bitchord)",
    val title: String = "BitChord Track",
    val artist: String = "BitChord Playback",
    val container: String = "FLAC / WAV",
    @SerializedName("sample_rate") val sampleRate: Int = 44100,
    @SerializedName("bit_depth") val bitDepth: Int = 16,
    val channels: Int = 2,
    val badge: String = "BIT-EXACT"
)

@Immutable
data class Node2Decoder(
    val engine: String = "c2.android.flac.decoder / FFmpeg (Float32)",
    val mode: String = "AudioTrack Float32",
    val badge: String = "LOSSLESS"
)

@Immutable
data class Node3Resampler(
    val status: String = "Bypassed (1:1 Bit-Exact)",
    val ratio: String = "1:1",
    @SerializedName("lock_48k_defeated") val lock48kDefeated: Boolean = true,
    val badge: String = "BYPASS ACTIVE"
)

@Immutable
data class Node4Dsp(
    val status: String = "Bit-Perfect Direct Pass (All FX Bypassed)",
    @SerializedName("volume_truncation") val volumeTruncation: String = "Zero Loss",
    val badge: String = "BIT-PERFECT"
)

@Immutable
data class Node5Endpoint(
    val type: String = "NONE",
    @SerializedName("device_name") val deviceName: String = "Audio Endpoint",
    val chip: String = "Integrated Audio HAL",
    @SerializedName("hardware_rate") val hardwareRate: String = "Standby",
    val format: String = "S24_3LE",
    @SerializedName("bit_depth") val bitDepth: Int = 24,
    val transport: String = "Vendor Direct PCM",
    @SerializedName("bit_exact") val bitExact: Boolean = true,
    @SerializedName("period_size") val periodSize: Int = 0,
    @SerializedName("buffer_size") val bufferSize: Int = 0,
    val badge: String = "HAL DIRECT"
)

@Immutable
data class BluetoothTelemetry(
    @SerializedName("device_name") val deviceName: String = "Hi-Res Wireless Headset",
    val codec: String = "LHDC_V3",
    @SerializedName("sample_rate") val sampleRate: Int = 96000,
    @SerializedName("bit_depth") val bitDepth: Int = 24,
    val bitrate: String = "400 kbps (ABR)",
    val verdict: String = "HI-RES WIRELESS (Lossy Bluetooth transmission via AudioFlinger)"
)

@Immutable
data class ControlsConfig(
    @SerializedName("direct_bit_perfect_mode") val directBitPerfectMode: Boolean = true,
    @SerializedName("usb_hardware_volume") val usbHardwareVolume: Boolean = true,
    @SerializedName("internal_direct_output") val internalDirectOutput: Boolean = true,
    @SerializedName("bit_perfect_rate_switching") val bitPerfectRateSwitching: Boolean = true,
    @SerializedName("flac_replay_gain_mode") val flacReplayGainMode: String = "BIT_PERFECT_OFF",
    @SerializedName("audio_buffer_sizing") val audioBufferSizing: String = "AUDIOPHILE_STABLE",
    @SerializedName("hardware_volume_level") val hardwareVolumeLevel: Int = 100
)

@Immutable
data class ApiResponse(
    val success: Boolean = false,
    val message: String? = null,
    val error: String? = null
)
