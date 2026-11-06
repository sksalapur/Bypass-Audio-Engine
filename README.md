# 💎 Bypass Audiophile Engine — High-Performance Audio HAL Mod & Companion Suite

**Bypass Audiophile Engine** is an audiophile-grade system modification and companion suite for rooted Android devices (**APatch / KernelSU / Magisk**). It acts as a transparent, zero-latency system hardware bridge between physical audio devices (USB DACs, internal DACs, and Hi-Res Bluetooth codecs like LHDC v5 / LDAC) and Android media players—featuring native Jetpack Compose telemetry, autonomous TWS controls, and dynamic Audio HAL bypass.

---

## 🌟 Key Capabilities

1. **Complete AudioFlinger 48 kHz Bypass**: Unlocks native sample rate switching (44.1, 48, 88.2, 96, 176.4, 192, 352.8, 384 kHz) and direct HAL threads, eliminating Android's 48 kHz resampling and software bit-depth truncation.
2. **Transparent BitChord Hardware Bridge**: BitChord's built-in Audio Pipeline dialog and telemetry reflect **AudioFlinger Mixer: Bypassed** and **Direct Pass: Active (100% Bit-Exact)** via an update-resilient runtime LSPosed hook.
3. **Zero-Lock Telemetry Polling (Crackle-Proof)**: Never calls `dumpsys media.audio_flinger` (which halts playback threads with up to 45 ms mutex locks). Reads kernel ALSA `/proc/asound` directly in **0.05 ms**, completely preventing buffer underruns and crackles.
4. **Universal Decoder & Codec Inspection**: Integrates fast non-blocking `dumpsys media.metrics` and `dumpsys media_session` inspection to monitor native track resolution and containers (FLAC, Opus, AAC, ALAC) across BitChord, Apple Music, TIDAL, Qobuz, Poweramp, and more.
5. **Autonomous TWS Earbud Control (QuickBuds RFCOMM)**: Direct Bluetooth RFCOMM hardware communication for realme/OnePlus/Oppo earbuds (including realme Buds T500 Pro) providing ANC mode toggles (ANC / Normal / Transparency) and live hardware battery levels with zero OEM background bloatware.
6. **Two Live Interactive Home Screen Widgets**:
   - **Hardware Audio Signal Chain Widget**: Real-time 5-node live signal chain visualization with autonomous one-shot refresh.
   - **TWS ANC & Battery Widget**: Instant 1-tap ANC mode switching and battery telemetry.
7. **Native Jetpack Compose Companion App & Cyberpunk WebUI (`:9090`)**: Studio Dark control center for direct HAL switching, UAC2 hardware volume attenuation (ALSA mixer registers), FLAC normalization, and ring buffer tuning.

---

## ⚠️ Prerequisites & Framework Requirements

> [!IMPORTANT]
> **LSPosed Framework Requirement:**
> This project requires **LSPosed** to hook into BitChord and expose dynamic hardware telemetry.
> Because the official/legacy LSPosed project has been **discontinued and archived**, you **must use the modern community continuation fork: [LSPosed_mod](https://github.com/mywalkb/LSPosed_mod)**.
> 
> - **Repository**: [https://github.com/mywalkb/LSPosed_mod](https://github.com/mywalkb/LSPosed_mod)
> - **Releases**: [https://github.com/mywalkb/LSPosed_mod/releases](https://github.com/mywalkb/LSPosed_mod/releases)
> 
> Flash the Zygisk release of **LSPosed_mod** in your root manager (APatch, KernelSU, or Magisk) before installing the Bypass Companion APK.

---

## 📂 Project Structure

```text
Bypass-Audio-Engine/
├── bypass-audio/                               # Root APatch / Magisk / KernelSU Module
│   ├── module.prop                             # Module metadata (v0.1)
│   ├── customize.sh                            # Module installer & permission setup
│   ├── service.sh                              # Background daemon supervisor
│   ├── system.prop                             # Audiophile props (super_hifi=0 anti-crackle)
│   ├── sepolicy.rule                           # Injects permissive rules for USB & audio endpoints
│   ├── system/vendor/etc/
│   │   ├── usb_audio_policy_configuration.xml  # DIRECT & RAW mixPorts for all sample rates
│   │   └── audio_policy_configuration.xml      # Internal direct offload PCM policy
│   ├── scripts/
│   │   ├── telemetry.sh                        # 0.05 ms kernel ALSA & fast media.metrics reader
│   │   ├── controls.sh                         # Manages bitchord_settings.xml & ALSA mixer controls
│   │   └── server.sh                           # Micro HTTP daemon routing GET/POST requests
│   └── webroot/                                # Integrated Cyberpunk WebUI (:9090)
│
├── bypass-companion/                           # Kotlin Jetpack Compose Companion & LSPosed Hook App
│   ├── app/src/main/java/com/music/bypass/
│   │   ├── MainActivity.kt                     # Studio Dark compose control center
│   │   ├── hook/                               # LSPosed runtime hooks (BitChord & Realme Link)
│   │   ├── service/                            # Autonomous QuickBuds RFCOMM Bluetooth service
│   │   ├── widget/                             # Home screen widgets (Signal Chain & TWS ANC)
│   │   └── data/                               # HTTP client & models
│   └── build.gradle.kts                        # Gradle build configuration (SDK 35, Java 21)
│
├── stock_reference/                            # Original unpatched stock audio policies for reference
├── package_module.py                           # Automated build and ADB deployment script
└── README.md
```

---

## 🌉 How the Direct Audio HAL Routing Works

### 1. Direct Playback HAL Flag Injection
The module overlays `/vendor/etc/usb_audio_policy_configuration.xml`:

```xml
<mixPort name="usb_direct_output" role="source"
         flags="AUDIO_OUTPUT_FLAG_DIRECT|AUDIO_OUTPUT_FLAG_RAW">
    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT,AUDIO_FORMAT_PCM_24_BIT_PACKED,AUDIO_FORMAT_PCM_8_24_BIT,AUDIO_FORMAT_PCM_32_BIT,AUDIO_FORMAT_PCM_FLOAT"
             samplingRates="44100,48000,88200,96000,176400,192000,352800,384000"
             channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
</mixPort>
```

When an audiophile player queries direct output capabilities, Android's `AudioPolicy` assigns it a direct HAL thread instead of a mixer thread.

### 2. Live 5-Node Audio Signal Chain
```text
[ SOURCE ] ────▶ [ DECODER ] ────▶ [ RESAMPLER ] ────▶ [ DSP ] ────▶ [ ENDPOINT ]
BitChord          FLAC / WAV       Bypassed             Direct Pass   Chu II DSP / realme Buds
96.0 kHz          24-bit           1:1 Bit-Exact        Bit-Perfect   96.0 kHz / 24b (LHDC v5)
```

---

## 🚀 Installation & Setup Guide

### Step 1: Install LSPosed_mod
1. Download the latest Zygisk release from [LSPosed_mod Releases](https://github.com/mywalkb/LSPosed_mod/releases).
2. Flash it in **APatch / KernelSU / Magisk** and reboot.

### Step 2: Install Bypass Audiophile Engine (Root Module)
1. Download `bypass-audiophile-engine.zip` from the [Releases](../../releases) page.
2. In your root manager (APatch, KernelSU, or Magisk), go to **Modules** ➔ **Install from storage**.
3. Select `bypass-audiophile-engine.zip` and reboot your device.

### Step 3: Install Bypass Companion APK
1. Download and install `BypassCompanion-v1.0.apk`.
2. Open **LSPosed_mod**, enable the **Bypass Audiophile Engine** module, and check `BitChord` in the module's scope.
3. Open the Bypass Companion app to access your live audio signal chain, hardware controls, and widgets.

---

## 🎛️ Hardware Controls Reference

| Control | Description | Action |
|---|---|---|
| **USB Exclusive Output** | Direct ALSA endpoints (`/dev/bus/usb/*/*`) | Bypasses Android userland audio services |
| **Bit-Perfect Rate Switching** | Reconfigures DAC clock dynamically | Switches hardware rates (44.1 - 192 kHz) without resampling |
| **Internal Direct Output** | Bypasses system mixer on 3.5mm jack | Activates vendor direct PCM route |
| **DAC Hardware Volume** | Real UAC2 register attenuation | Controls DAC hardware volume without truncating PCM bits |
| **FLAC Normalization** | ReplayGain / Bit-Perfect override | When set to `BIT_PERFECT_OFF`, disables all DSP gains |
| **Audio Buffer Sizing** | Ring buffer latency tuning | Switches between Low Latency (250ms) and Stable (1.5s) |
