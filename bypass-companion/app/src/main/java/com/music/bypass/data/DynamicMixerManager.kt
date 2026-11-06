package com.music.bypass.data

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DynamicMixerManager {

    private const val TAG = "DynamicMixerManager"
    private const val PREFS_NAME = "bypass_dynamic_mixer_prefs"
    private const val KEY_DYNAMIC_CLOCK = "dynamic_clock_enabled"
    private const val KEY_ANTI_STUTTER = "anti_stutter_enabled"
    private const val KEY_WHITELIST = "whitelisted_packages"

    val DEFAULT_WHITELISTED_APPS = setOf(
        "com.music.bitchord",
        "com.apple.android.music",
        "com.aspiro.tidal",
        "com.qobuz.music",
        "com.spotify.music",
        "com.maxmpz.audioplayer",
        "com.google.android.apps.youtube.music",
        "com.extreamsd.usbaudioplayerpro",
        "com.hiby.music",
        "com.fiio.music",
        "deezer.android.app"
    )

    val KNOWN_VIDEO_APPS = setOf(
        "com.google.android.youtube",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.instagram.android",
        "com.netflix.mediaclient",
        "com.facebook.katana"
    )

    private val _activeStatusText = MutableStateFlow("Ready (Standard Mixer)")
    val activeStatusText: StateFlow<String> = _activeStatusText.asStateFlow()

    @Volatile
    private var lastAppliedRate: Int = 0

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDynamicClockEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_DYNAMIC_CLOCK, true)

    fun setDynamicClockEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DYNAMIC_CLOCK, enabled).apply()
        if (!enabled) {
            clearPreferredMixerAttributes(context)
            _activeStatusText.value = "Disabled (Standard 48 kHz)"
        }
    }

    fun isAntiStutterEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_ANTI_STUTTER, true)

    fun setAntiStutterEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ANTI_STUTTER, enabled).apply()
    }

    fun getWhitelistedApps(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_WHITELIST, null) ?: DEFAULT_WHITELISTED_APPS
    }

    fun setAppWhitelisted(context: Context, pkg: String, whitelisted: Boolean) {
        val current = getWhitelistedApps(context).toMutableSet()
        if (whitelisted) current.add(pkg) else current.remove(pkg)
        getPrefs(context).edit().putStringSet(KEY_WHITELIST, current).apply()
    }

    fun isAppWhitelisted(context: Context, pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return getWhitelistedApps(context).contains(pkg)
    }

    /**
     * Called whenever a new telemetry packet arrives from the daemon.
     * Evaluates whether to dynamically re-clock the USB DAC or protect video sync.
     */
    fun onTelemetryUpdated(context: Context, telemetry: TelemetryResponse) {
        if (!isDynamicClockEnabled(context)) return

        val isUsb = telemetry.isUsbActive || telemetry.activeEndpoint == "USB_DAC"
        val activePkg = telemetry.activePackage ?: ""
        val isVideo = telemetry.isVideoApp || KNOWN_VIDEO_APPS.any { activePkg.contains(it) }
        val isWhitelisted = isAppWhitelisted(context, activePkg)
        val sourceRate = telemetry.signalChain.node1Source.sampleRate
        val isPlaying = telemetry.playbackState == "PLAYING"

        if (isVideo && isAntiStutterEnabled(context)) {
            // Video app detected: enforce standard 48 kHz mixer clock to prevent frame drops
            if (lastAppliedRate != 0 && lastAppliedRate != 48000) {
                Log.i(TAG, "Video app active ($activePkg) - restoring standard 48 kHz mixer to prevent stutter")
                clearPreferredMixerAttributes(context)
                lastAppliedRate = 48000
            }
            _activeStatusText.value = "Video Safe Mode: 48 kHz (Frame-Sync Protected)"
            return
        }

        if (isUsb && isPlaying && isWhitelisted && sourceRate > 0) {
            if (sourceRate != lastAppliedRate) {
                Log.i(TAG, "Music app active ($activePkg) - dynamically aligning DAC clock to $sourceRate Hz")
                val success = setPreferredMixerAttributes(context, sourceRate, 24)
                if (success) {
                    lastAppliedRate = sourceRate
                    _activeStatusText.value = "1:1 Dynamic Lock: ${sourceRate / 1000.0} kHz"
                } else {
                    _activeStatusText.value = "DAC Clock: ${sourceRate / 1000.0} kHz (Direct HAL)"
                }
            }
        } else if (!isPlaying && lastAppliedRate != 0) {
            // Playback stopped or in standby
            _activeStatusText.value = "Standby (Clock Ready)"
        }
    }

    /**
     * Android 14+ setPreferredMixerAttributes dynamic sample rate clocking
     */
    fun setPreferredMixerAttributes(context: Context, sampleRateHz: Int, bitDepth: Int = 24): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }

        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val usbDev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: return false

            val mediaAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val encoding = when (bitDepth) {
                16 -> AudioFormat.ENCODING_PCM_16BIT
                32 -> AudioFormat.ENCODING_PCM_32BIT
                else -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            }

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRateHz)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(encoding)
                .build()

            val mixerAttrs = AudioMixerAttributes.Builder(format)
                .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT)
                .build()

            val success = am.setPreferredMixerAttributes(mediaAttrs, usbDev, mixerAttrs)
            Log.d(TAG, "setPreferredMixerAttributes($sampleRateHz Hz, bitDepth=$bitDepth): $success")
            success
        } catch (e: Exception) {
            Log.w(TAG, "Could not setPreferredMixerAttributes", e)
            false
        }
    }

    fun clearPreferredMixerAttributes(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }

        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val usbDev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: return false

            val mediaAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            am.clearPreferredMixerAttributes(mediaAttrs, usbDev)
            lastAppliedRate = 0
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not clearPreferredMixerAttributes", e)
            false
        }
    }
}

