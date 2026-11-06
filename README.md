# 💎 Bypass Audiophile Engine — High-Performance Magisk/APatch Module & BitChord Hardware Bridge

**Bypass Audiophile Engine** is a zero-latency, high-performance root module for Android (Magisk / APatch / KernelSU) designed to act as a **transparent system-level hardware bridge** between physical audio hardware (USB DACs like the Moondrop Chu II DSP, internal phone DACs, and Bluetooth codecs) and the unmodified **BitChord** application (`com.music.bitchord`).

---

## 🌟 Key Capabilities

1. **Complete AudioFlinger 48 kHz Bypass**: Bypasses Android's 48 kHz system resampler, mixer thread, and software bit-depth truncation at the HAL and kernel layer.
2. **Transparent BitChord Hardware Bridge**: BitChord's built-in Audio Pipeline dialog and telemetry reflect **AudioFlinger Mixer: Bypassed** and **Direct Pass: Active** with 100% bit-exact fidelity without modifying the BitChord APK.
3. **Zero-Lock Telemetry Polling (Crackle-Proof)**: Replaces dangerous `dumpsys media.audio_flinger` calls with instant 0.05 ms kernel ALSA `/proc/asound` readers, eliminating audio crackling and buffer underruns (xruns).
4. **Integrated Audiophile WebUI (`http://127.0.0.1:9090`)**: Cyberpunk neon dark dashboard featuring a live 5-node audio signal chain, Bluetooth codec inspection, and live hardware control toggles.
5. **Strict Bluetooth Suppression**: Prevents fake or ghost wireless telemetry whenever a physical USB DAC or internal DAC is actively streaming.

---

## 📂 Project Directory Structure

```text
bypass-audio/
├── module.prop                                 # Magisk / APatch / KernelSU metadata
├── customize.sh                                # Module installer & permission setup
├── service.sh                                  # Late-start daemon launcher & supervisor
├── system.prop                                 # Audiophile system properties (super_hifi=0 anti-crackle)
├── sepolicy.rule                               # Injects permissive rules for USB & audio devices
├── system/
│   └── vendor/
│       └── etc/
│           ├── usb_audio_policy_configuration.xml    # DIRECT & RAW mixPorts for all sample rates
│           └── audio_policy_configuration.xml        # Internal direct offload PCM policy
├── scripts/
│   ├── telemetry.sh                            # 0.05 ms kernel ALSA & fast media_session reader
│   ├── controls.sh                             # Manages bitchord_settings.xml & ALSA mixer controls
│   └── server.sh                               # Micro HTTP daemon routing GET/POST requests
└── webroot/
    ├── index.html                              # Audiophile WebUI dashboard with 5-node pipeline
    ├── style.css                               # Responsive neon-dark theme with CSS Grid signal chain
    └── app.js                                  # Polling loop & instant setting toggles

package_module.py                               # Automation script to zip and deploy via ADB
bypass-audiophile-engine.zip                    # Ready-to-flash module zip archive
```

---

## 🌉 How the Rooted Bridge Works Without Modifying BitChord

### 1. Direct Playback HAL Flag Injection
BitChord queries Android's `AudioTrack.isDirectPlaybackSupported()` to check if the HAL supports direct hardware output. The module overlays `/vendor/etc/audio_policy_configuration.xml` and `/system/vendor/etc/usb_audio_policy_configuration.xml`:

```xml
<mixPort name="usb_direct_output" role="source"
         flags="AUDIO_OUTPUT_FLAG_DIRECT|AUDIO_OUTPUT_FLAG_RAW">
    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT,AUDIO_FORMAT_PCM_24_BIT_PACKED,AUDIO_FORMAT_PCM_8_24_BIT,AUDIO_FORMAT_PCM_32_BIT,AUDIO_FORMAT_PCM_FLOAT"
             samplingRates="44100,48000,88200,96000,176400,192000,352800,384000"
             channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
</mixPort>
```

When BitChord queries direct output capabilities, Android's AudioPolicy assigns BitChord a direct HAL thread instead of a mixer thread. BitChord automatically marks:
- **Transport: Direct Output / Direct USB**
- **AudioFlinger Mixer: Bypassed**
- **Bit-Exact: Active (100% Fidelity)**

### 2. State & Settings Synchronization
BitChord persists its audiophile pipeline configuration in:
`/data/data/com.music.bitchord/shared_prefs/bitchord_settings.xml`

The module's `controls.sh` daemon modifies this file directly as root with BitChord's UID/GID (e.g. `10003:10003`, chmod `660`) and fires Android broadcast intents:
- `am broadcast -a com.music.bitchord.ACTION_SETTINGS_CHANGED`
- `am broadcast -a com.music.bitchord.RELOAD_AUDIO_SINK`
- `am startservice -a com.music.bitchord.ACTION_RELOAD_PIPELINE com.music.bitchord/.audio.AudioSinkService`

This reloads BitChord's audio sink dynamically without restarting the app.

---

## 🎛️ The 6 Primary Hardware Controls

| Control | Setting Key | Hardware Action |
|---|---|---|
| **1. USB Exclusive Output** | `direct_bit_perfect_mode` | Sets `/dev/bus/usb/*/*` to `chmod 666`, unlocks UAC2 endpoints in SELinux |
| **2. DAC Hardware Volume** | `usb_hardware_volume` & `hardware_volume_level` | Adjusts ALSA mixer UAC2 Feature Unit (`tinymix` / `amixer`) directly; zero PCM bit truncation |
| **3. Internal Direct Output** | `internal_direct_output` | Bypasses system mixer on internal phone speakers & 3.5mm jack via Qualcomm Hexagon DSP / MediaTek HiFi |
| **4. Bit-Perfect Rate Switching** | `bit_perfect_rate_switching` | Unlocks native sample rate switching (44.1, 96, 192 kHz) without resampling to 48 kHz |
| **5. FLAC Normalization Bridge** | `flac_replay_gain_mode` (`BIT_PERFECT_OFF`, `REPLAY_GAIN_TRACK`, `DYNAMIC_NORMALIZATION`) | When `BIT_PERFECT_OFF`, explicitly disables all DSP gains for 100% bit-exact transmission |
| **6. Audio Buffer Sizing** | `audio_buffer_sizing` (`LOW_LATENCY`, `STANDARD`, `AUDIOPHILE_STABLE`) | Tunes AudioTrack sink buffer length while enforcing `vendor.audio.usb.super_hifi=0` |

---

## ⚠️ Anti-Crackle Rules & Hardware Constraints

1. **Zero-Lock Telemetry Polling**:
   - `dumpsys media.audio_flinger` acquires AudioFlinger's `mLock` mutex for up to **45 ms**, halting the audio write thread and causing severe buffer underruns against the 21 ms kernel buffer.
   - **Solution**: Reads kernel ALSA hardware parameters directly from `/proc/asound/cardX/pcm0p/sub0/hw_params` (takes **0.05 ms**, zero lock contention). Fast non-blocking `dumpsys media_session` (< 5 ms) is used only for track metadata.
2. **Moondrop Chu II DSP (KT0210) Constraint**:
   - The KTMicro KT0210 DSP runs on USB Full-Speed (12 Mbps, 1 ms frames) supporting 44.1, 48, 88.2, 96 kHz @ S16_LE / S24_3LE. It does **not** support 192 kHz clocks.
   - `vendor.audio.usb.super_hifi=1` forces a 512-frame (5.3 ms) period size that drops isochronous packets on Full-Speed DACs. **`vendor.audio.usb.super_hifi=0` is strictly enforced**.
3. **Android `noexec` Partition**:
   - `/data/adb/modules` is mounted `noexec`. Scripts are invoked explicitly via shell: `/system/bin/sh ...`.
4. **Toybox `nc` Syntax**:
   - Requires exact flag ordering: `toybox nc -s 127.0.0.1 -p 9090 -L /system/bin/sh "$MODDIR/scripts/server.sh"`.
5. **CRLF Query Parameter Sanitization**:
   - HTTP request lines end with `\r\n`. Carriage returns are stripped (`tr -d '\r\n '`) to prevent corrupting query parameters.

---

## 📡 Live 5-Node Audio Signal Chain (`http://127.0.0.1:9090`)

```text
[ SOURCE ] ────▶ [ DECODER ] ────▶ [ RESAMPLER ] ────▶ [ DSP ] ────▶ [ ENDPOINT ]
BitChord          FLAC / WAV       Bypassed             Direct Pass   Chu II DSP
96.0 kHz          24-bit           1:1 Bit-Exact        Bit-Perfect   96.0 kHz / 24b
```

### Top Pipeline Display (Anti-Truncation CSS Grid)
```css
grid-template-columns: 1fr auto 1fr auto 1fr;
gap: 4px;
white-space: nowrap;
```
- Node 1: `96.0 kHz` (or `Standby`)
- Node 2: `➔`
- Node 3: `Direct Pass`
- Node 4: `➔`
- Node 5: `96.0 kHz • 24b` (or `Standby • 24b`) — **Never cut off on small phone screens**.

---

## 🚀 Deployment & Installation Guide

### Option 1: Automatic ADB Deployment (Recommended)
Connect your rooted Android device via USB and run:

```bash
# Build flashable zip, install directly, start WebUI, and forward port 9090
python package_module.py --install --start
```

### Option 2: Flash via Magisk / KernelSU / APatch Manager
1. Generate the flashable zip:
   ```bash
   python package_module.py --zip-only
   ```
2. Transfer `bypass-audiophile-engine.zip` to your device.
3. Open Magisk, KernelSU, or APatch app ➔ **Modules** ➔ **Install from storage**.
4. Select `bypass-audiophile-engine.zip` and reboot.
5. Access the WebUI at:
   `http://127.0.0.1:9090`

### Option 3: Manual Shell Launch
To start or test the daemon directly without rebooting:
```bash
adb shell su -c "nohup toybox nc -s 127.0.0.1 -p 9090 -L /system/bin/sh /data/adb/modules/bypass-audio/scripts/server.sh </dev/null >/dev/null 2>&1 &"
adb forward tcp:9090 tcp:9090
```
Open `http://localhost:9090` in your computer's browser.

