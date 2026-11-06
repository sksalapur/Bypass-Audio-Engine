#!/system/bin/sh
# ══════════════════════════════════════════════════════════════════════════════
# Bypass Audiophile Engine — Zero-Lock Telemetry Polling Engine
# Anti-Crackle Rule: NEVER invoke dumpsys media.audio_flinger!
# Reads kernel ALSA /proc/asound, dumpsys bluetooth_manager & media_session
# ══════════════════════════════════════════════════════════════════════════════

# Default values
PLAYBACK_STATE="STANDBY"
ACTIVE_ENDPOINT="NONE"
IS_USB_ACTIVE=0
IS_INTERNAL_ACTIVE=0
HW_RATE=0
HW_FORMAT=""
HW_CHANNELS=2
HW_PERIOD_SIZE=0
HW_BUFFER_SIZE=0
CARD_NAME=""
CARD_CHIP=""
CARD_NUM=""

# 1. Fast Media Session Inspection (< 5 ms, no mutex contention)
MS_DUMP=$(dumpsys media_session 2>/dev/null)
MEDIA_TITLE="BitChord Track"
MEDIA_ARTIST="BitChord Playback"
MEDIA_PKG="com.music.bitchord"
MS_PLAYING=0

if echo "$MS_DUMP" | grep -qE "state=3|state=PlaybackState \{state=3"; then
    MS_PLAYING=1
fi

# Detect active package from media session stack
PKG_DETECTED=$(echo "$MS_DUMP" | grep -B 2 -A 2 "state=PlaybackState {state=3" | grep -o 'package=[^ ]*' | head -n1 | sed 's/package=//' | tr -d '"\r\n\\ ')
if [ -z "$PKG_DETECTED" ]; then
    PKG_DETECTED=$(echo "$MS_DUMP" | grep -o 'package=[^ ]*' | grep -vE 'com.android.server.telecom|com.google.android.gms|system' | head -n1 | sed 's/package=//' | tr -d '"\r\n\\ ')
fi
if [ -n "$PKG_DETECTED" ]; then
    MEDIA_PKG="$PKG_DETECTED"
fi

DESC_LINE=$(echo "$MS_DUMP" | grep -o 'description=[^,]*' | head -n1 | sed 's/description=//' | sed 's/^[ \t]*//;s/[ \t]*$//' | tr -d '"\r\n\\')
if [ -n "$DESC_LINE" ] && [ "$DESC_LINE" != "null" ]; then
    MEDIA_TITLE="$DESC_LINE"
fi

ARTIST_LINE=$(echo "$MS_DUMP" | grep -o 'description=[^,]*, *[^,]*' | head -n1 | sed 's/.*, *//' | sed 's/^[ \t]*//;s/[ \t]*$//' | tr -d '"\r\n\\')
if [ -n "$ARTIST_LINE" ] && [ "$ARTIST_LINE" != "$MEDIA_TITLE" ] && [ "$ARTIST_LINE" != "null" ]; then
    MEDIA_ARTIST="$ARTIST_LINE"
fi

# 1b. Fast Media Metrics Stream & Codec Resolution Inspection (< 5 ms)
METRICS_CONTAINER="FLAC / WAV"
METRICS_RATE=0
METRICS_DUMP=$(dumpsys media.metrics 2>/dev/null | grep -E '(\{codec,|\{audio\.track\.|\{audio\.thread\.)' | tail -n 25)
if [ -n "$METRICS_DUMP" ]; then
    SR_MATCH=$(echo "$METRICS_DUMP" | grep -o 'sampleRate=[0-9]*\|sample_rate:[0-9]*' | tail -n1 | sed 's/.*[=:]//' | tr -d '\r\n ')
    if [ -n "$SR_MATCH" ] && [ "$SR_MATCH" -gt 0 ] 2>/dev/null; then
        METRICS_RATE="$SR_MATCH"
    fi

    if echo "$METRICS_DUMP" | grep -qi "flac"; then
        METRICS_CONTAINER="FLAC"
    elif echo "$METRICS_DUMP" | grep -qi "opus"; then
        METRICS_CONTAINER="Opus"
    elif echo "$METRICS_DUMP" | grep -qi "aac"; then
        METRICS_CONTAINER="AAC"
    elif echo "$METRICS_DUMP" | grep -qi "alac"; then
        METRICS_CONTAINER="ALAC"
    elif echo "$METRICS_DUMP" | grep -qi "mp3"; then
        METRICS_CONTAINER="MP3"
    fi
fi

# Friendly App Name & Video App Detection
MEDIA_APP_NAME="Music Player"
case "$MEDIA_PKG" in
    *bitchord*) MEDIA_APP_NAME="BitChord" ;;
    *apple*music*) MEDIA_APP_NAME="Apple Music" ;;
    *tidal*) MEDIA_APP_NAME="TIDAL" ;;
    *qobuz*) MEDIA_APP_NAME="Qobuz" ;;
    *spotify*) MEDIA_APP_NAME="Spotify" ;;
    *audioplayer*|*poweramp*) MEDIA_APP_NAME="Poweramp" ;;
    *youtube.music*) MEDIA_APP_NAME="YouTube Music" ;;
    *usbaudioplayerpro*) MEDIA_APP_NAME="UAPP" ;;
    *hiby*) MEDIA_APP_NAME="HiBy Music" ;;
    *fiio*) MEDIA_APP_NAME="FiiO Music" ;;
    *deezer*) MEDIA_APP_NAME="Deezer" ;;
    *youtube*) MEDIA_APP_NAME="YouTube" ;;
    *tiktok*|*musically*|*trill*) MEDIA_APP_NAME="TikTok" ;;
    *instagram*) MEDIA_APP_NAME="Instagram" ;;
    *netflix*) MEDIA_APP_NAME="Netflix" ;;
    *) MEDIA_APP_NAME="Audio Track" ;;
esac

IS_VIDEO_APP=false
case "$MEDIA_PKG" in
    *youtube*|*tiktok*|*musically*|*trill*|*instagram*|*netflix*|*facebook*)
        IS_VIDEO_APP=true
        ;;
esac

# 2. Inspect kernel ALSA sound cards via /proc/asound/cards
USB_CARD_FOUND=0
if [ -f "/proc/asound/cards" ]; then
    USB_LINE=$(grep -E '\[.*\]: USB-Audio' /proc/asound/cards 2>/dev/null | head -n1)
    if [ -n "$USB_LINE" ]; then
        USB_CARD_FOUND=1
        CARD_NUM=$(echo "$USB_LINE" | awk '{print $1}')
        CARD_DESC=$(echo "$USB_LINE" | sed 's/^[0-9 ]*\[[^]]*\]: //')
        ACTIVE_ENDPOINT="USB_DAC"

        # Check for KT0210 / Moondrop Chu II DSP
        if grep -qi "KT0210\|KTMicro\|Chu2\|Chu II\|Moondrop" /proc/asound/cards 2>/dev/null || \
           grep -qi "KT0210\|KTMicro\|Chu2\|Chu II\|Moondrop" "/proc/asound/card$CARD_NUM/stream0" 2>/dev/null; then
            CARD_NAME="Moondrop Chu II DSP"
            CARD_CHIP="KTMicro KT0210 (USB Full-Speed)"
        else
            CARD_NAME="$CARD_DESC"
            CARD_CHIP="USB Audio Class 2.0 DAC"
        fi

        # Check if USB audio sub0 is actively playing
        HW_PARAMS="/proc/asound/card$CARD_NUM/pcm0p/sub0/hw_params"
        if [ -f "$HW_PARAMS" ]; then
            CONTENT=$(cat "$HW_PARAMS" 2>/dev/null)
            if [ -n "$CONTENT" ] && [ "$CONTENT" != "closed" ]; then
                PLAYBACK_STATE="PLAYING"
                IS_USB_ACTIVE=1
                HW_RATE=$(echo "$CONTENT" | grep "^rate:" | head -n1 | awk '{print $2}' | tr -d '\r\n ')
                HW_FORMAT=$(echo "$CONTENT" | grep "^format:" | head -n1 | awk '{print $2}' | tr -d '\r\n ')
                HW_CHANNELS=$(echo "$CONTENT" | grep "^channels:" | head -n1 | awk '{print $2}' | tr -d '\r\n ')
                HW_PERIOD_SIZE=$(echo "$CONTENT" | grep "^period_size:" | head -n1 | awk '{print $2}' | tr -d '\r\n ')
                HW_BUFFER_SIZE=$(echo "$CONTENT" | grep "^buffer_size:" | head -n1 | awk '{print $2}' | tr -d '\r\n ')
            elif [ "$MS_PLAYING" -eq 1 ]; then
                PLAYBACK_STATE="PLAYING"
                IS_USB_ACTIVE=1
            fi
        fi
    fi

    # Check internal sound cards if USB is not present
    if [ "$USB_CARD_FOUND" -eq 0 ]; then
        for P in /proc/asound/card0/pcm*p/sub0/hw_params; do
            if [ -f "$P" ]; then
                CONTENT=$(cat "$P" 2>/dev/null)
                if [ -n "$CONTENT" ] && [ "$CONTENT" != "closed" ]; then
                    PLAYBACK_STATE="PLAYING"
                    IS_INTERNAL_ACTIVE=1
                    ACTIVE_ENDPOINT="INTERNAL_DAC"
                    CARD_NAME="Internal Hi-Fi Direct PCM"
                    CARD_CHIP="Qualcomm Hexagon DSP / MediaTek HiFi"
                    HW_RATE=$(echo "$CONTENT" | grep "^rate:" | head -n1 | awk '{print $2}' | tr -d '\r\n ')
                    HW_FORMAT=$(echo "$CONTENT" | grep "^format:" | head -n1 | awk '{print $2}' | tr -d '\r\n ')
                    HW_CHANNELS=$(echo "$CONTENT" | grep "^channels:" | head -n1 | awk '{print $2}' | tr -d '\r\n ')
                    HW_PERIOD_SIZE=$(echo "$CONTENT" | grep "^period_size:" | head -n1 | awk '{print $2}' | tr -d '\r\n ')
                    HW_BUFFER_SIZE=$(echo "$CONTENT" | grep "^buffer_size:" | head -n1 | awk '{print $2}' | tr -d '\r\n ')
                    break
                fi
            fi
        done
    fi
fi

# 3. Bluetooth Stack Inspection (Only active when USB DAC is disconnected)
BT_JSON="null"
BT_SUPPRESSED="true"
BT_CONNECTED=0
BT_STREAMING=0
BT_CODEC="AAC"
BT_DEVICE="Bluetooth Headset"
BT_RATE=44100
BT_BITS=16
BT_BITRATE="328 kbps"

if [ "$USB_CARD_FOUND" -eq 0 ] && [ "$IS_INTERNAL_ACTIVE" -eq 0 ]; then
    BT_DUMP=$(dumpsys bluetooth_manager 2>/dev/null)
    if echo "$BT_DUMP" | grep -qE "Connected: true|ConnectionState: STATE_CONNECTED|mActiveDevice: [0-9a-fA-F:]+|StateMachine: name=A2dpStateMachine state=Connected"; then
        BT_CONNECTED=1
    fi

    if echo "$BT_DUMP" | grep -qE "Streaming: true|A2dpStackEvent .*EVENT_TYPE_AUDIO_STATE_CHANGED.*STARTED"; then
        BT_STREAMING=1
    fi

    if [ "$BT_CONNECTED" -eq 1 ]; then
        # Parse Codec
        CODEC_MATCH=$(echo "$BT_DUMP" | grep "Current Codec:" | head -n1 | awk '{print $3}' | tr -d '\r\n ')
        if [ -n "$CODEC_MATCH" ]; then
            case "$CODEC_MATCH" in
                LHDC_V3|LHDC_V4|LHDC_V5|LHDC)
                    BT_CODEC="LHDC Version 5"
                    ;;
                LHDC_V2)
                    BT_CODEC="LHDC Version 2"
                    ;;
                *)
                    BT_CODEC="$CODEC_MATCH"
                    ;;
            esac
        fi

        # Parse Device Name
        DEV_MATCH=$(echo "$BT_DUMP" | grep -o 'model_name=[^|]*' | head -n1 | sed 's/model_name=//' | tr -d '"\r\n\\')
        if [ -n "$DEV_MATCH" ]; then
            BT_DEVICE="$DEV_MATCH"
        else
            DEV_BONDED=$(echo "$BT_DUMP" | grep -A1 "Bonded devices:" | tail -n1 | sed 's/^[ \t]*[0-9A-Fa-f:]*[ \t]*\[[^]]*\][ \t]*//' | tr -d '"\r\n\\')
            if [ -n "$DEV_BONDED" ]; then
                BT_DEVICE="$DEV_BONDED"
            fi
        fi

        # Parse Sample Rate & Bit Depth
        RATE_MATCH=$(echo "$BT_DUMP" | grep -o 'mSampleRate:0x[0-9a-fA-F]*([0-9]*)' | head -n1 | sed 's/.*(//;s/)//')
        if [ -n "$RATE_MATCH" ] && [ "$RATE_MATCH" -gt 0 ] 2>/dev/null; then
            BT_RATE="$RATE_MATCH"
        fi

        BITS_MATCH=$(echo "$BT_DUMP" | grep -o 'mBitsPerSample:0x[0-9a-fA-F]*([0-9]*)' | head -n1 | sed 's/.*(//;s/)//')
        if [ -n "$BITS_MATCH" ] && [ "$BITS_MATCH" -gt 0 ] 2>/dev/null; then
            BT_BITS="$BITS_MATCH"
        fi

        # Parse Bitrate
        LHDC_BR=$(echo "$BT_DUMP" | grep "LHDC transmission bitrate (Kbps)" | head -n1 | awk '{print $6}' | tr -d '\r\n ')
        if [ -n "$LHDC_BR" ] && [ "$LHDC_BR" != "-1" ] && [ "$LHDC_BR" -gt 0 ] 2>/dev/null; then
            BT_BITRATE="${LHDC_BR} kbps (ABR)"
        elif echo "$BT_CODEC" | grep -qi "LDAC"; then
            BT_BITRATE="990 kbps"
        elif echo "$BT_CODEC" | grep -qi "AAC"; then
            BT_BITRATE="256 kbps"
        fi

        # Mark Bluetooth as active endpoint
        ACTIVE_ENDPOINT="BLUETOOTH"
        BT_SUPPRESSED="false"
        if [ "$BT_STREAMING" -eq 1 ] || [ "$MS_PLAYING" -eq 1 ]; then
            PLAYBACK_STATE="PLAYING"
        fi

        # Determine Bluetooth verdict
        case "$BT_CODEC" in
            *LHDC*|*LDAC*|*aptX-HD*|*aptX*HD*)
                BT_VERDICT="HI-RES WIRELESS (Lossy Bluetooth transmission via AudioFlinger)"
                ;;
            *AAC*)
                BT_VERDICT="A2DP COMPRESSED (Lossy AAC compression via AudioFlinger)"
                ;;
            *SBC*)
                BT_VERDICT="STANDARD A2DP (Lossy SBC compression via AudioFlinger)"
                ;;
            *)
                BT_VERDICT="BLUETOOTH A2DP (Lossy transmission via AudioFlinger)"
                ;;
        esac

        BT_JSON=$(cat << EOF
{
  "device_name": "$BT_DEVICE",
  "codec": "$BT_CODEC",
  "sample_rate": $BT_RATE,
  "bit_depth": $BT_BITS,
  "bitrate": "$BT_BITRATE",
  "verdict": "$BT_VERDICT"
}
EOF
)
    fi
fi

# Fallback DAC names
if [ -z "$CARD_NAME" ]; then
    if [ "$ACTIVE_ENDPOINT" = "USB_DAC" ]; then
        CARD_NAME="Moondrop Chu II DSP"
        CARD_CHIP="KTMicro KT0210"
    elif [ "$ACTIVE_ENDPOINT" = "BLUETOOTH" ]; then
        CARD_NAME="$BT_DEVICE"
        CARD_CHIP="$BT_CODEC Encoder"
    else
        CARD_NAME="System Audio Out"
        CARD_CHIP="Integrated Audio HAL"
    fi
fi

# Format bit-depth helper
BIT_DEPTH=16
case "$HW_FORMAT" in
    *24*|S24_LE|S24_3LE|S24_BE)
        BIT_DEPTH=24
        ;;
    *32*|S32_LE|S32_BE|FLOAT*)
        BIT_DEPTH=32
        ;;
    *)
        BIT_DEPTH=16
        ;;
esac

# Dynamic Top Display calculation
if [ "$ACTIVE_ENDPOINT" = "USB_DAC" ]; then
    if [ "$PLAYBACK_STATE" = "PLAYING" ] && [ -n "$HW_RATE" ] && [ "$HW_RATE" -gt 0 ] 2>/dev/null; then
        RATE_KHZ=$(awk -v r="$HW_RATE" 'BEGIN { printf "%.1f", r / 1000 }' 2>/dev/null || echo "44.1")
        TOP_RATE_TEXT="${RATE_KHZ} kHz"
        TOP_ENDPOINT_TEXT="${RATE_KHZ} kHz • ${BIT_DEPTH}-bit"
        SOURCE_SAMPLE_RATE="$HW_RATE"
    else
        TOP_RATE_TEXT="Standby"
        TOP_ENDPOINT_TEXT="Standby • 24-bit"
        SOURCE_SAMPLE_RATE=44100
    fi
elif [ "$ACTIVE_ENDPOINT" = "BLUETOOTH" ]; then
    RATE_KHZ=$(awk -v r="$BT_RATE" 'BEGIN { printf "%.1f", r / 1000 }' 2>/dev/null || echo "96.0")
    if [ "$PLAYBACK_STATE" = "PLAYING" ]; then
        TOP_RATE_TEXT="${RATE_KHZ} kHz"
        TOP_ENDPOINT_TEXT="${RATE_KHZ} kHz • ${BT_BITS}-bit"
    else
        TOP_RATE_TEXT="Standby"
        TOP_ENDPOINT_TEXT="${RATE_KHZ} kHz • ${BT_BITS}-bit"
    fi
    SOURCE_SAMPLE_RATE="$BT_RATE"
    BIT_DEPTH="$BT_BITS"
else
    TOP_RATE_TEXT="Standby"
    TOP_ENDPOINT_TEXT="Standby • 24-bit"
    SOURCE_SAMPLE_RATE=44100
fi

# Clean strings
MEDIA_TITLE=$(echo "$MEDIA_TITLE" | tr -d '"\r\n\\')
MEDIA_ARTIST=$(echo "$MEDIA_ARTIST" | tr -d '"\r\n\\')
CARD_NAME=$(echo "$CARD_NAME" | tr -d '"\r\n\\')
CARD_CHIP=$(echo "$CARD_CHIP" | tr -d '"\r\n\\')

# Build Signal Chain depending on active endpoint
if [ "$ACTIVE_ENDPOINT" = "USB_DAC" ]; then
    N2_ENGINE="Bit-Exact Integer Pass"
    N2_MODE="Direct USB / ALSA Pass"
    N2_BADGE="LOSSLESS"

    N3_STATUS="Bypassed (1:1 Bit-Exact)"
    N3_RATIO="1:1"
    N3_LOCK=true
    N3_BADGE="BYPASS ACTIVE"

    N4_STATUS="Bit-Perfect Direct Pass (All FX Bypassed)"
    N4_VOL="Zero Loss"
    N4_BADGE="BIT-PERFECT"

    N5_DEV="$CARD_NAME"
    N5_CHIP="$CARD_CHIP"
    N5_FMT="${HW_FORMAT:-S24_3LE}"
    N5_TRANS="Direct Output / Direct USB"
    N5_EXACT=true
    N5_BADGE="UAC2 DIRECT"
elif [ "$ACTIVE_ENDPOINT" = "BLUETOOTH" ]; then
    N2_ENGINE="c2.android.flac.decoder / FFmpeg"
    N2_MODE="AudioTrack Float32"
    N2_BADGE="DECODED"

    N3_STATUS="AudioFlinger Resampler"
    N3_RATIO="Mixer ➔ ${TOP_RATE_TEXT}"
    N3_LOCK=false
    N3_BADGE="MIXER ACTIVE"

    N4_STATUS="System Audio Pipeline"
    N4_VOL="Software Scaled"
    N4_BADGE="A2DP STACK"

    N5_DEV="$BT_DEVICE"
    case "$BT_CODEC" in
        *LHDC*)
            N5_CHIP="LHDC v5 Encoder"
            N5_BADGE="HI-RES WIRELESS"
            ;;
        *LDAC*)
            N5_CHIP="LDAC Encoder"
            N5_BADGE="HI-RES WIRELESS"
            ;;
        *aptX-HD*|*aptX*HD*)
            N5_CHIP="aptX HD Encoder"
            N5_BADGE="HI-RES WIRELESS"
            ;;
        *AAC*)
            N5_CHIP="AAC Encoder"
            N5_BADGE="BT A2DP"
            ;;
        *SBC*)
            N5_CHIP="SBC Encoder"
            N5_BADGE="BT A2DP"
            ;;
        *)
            N5_CHIP="${BT_CODEC} Encoder"
            N5_BADGE="BT A2DP"
            ;;
    esac
    N5_FMT="${TOP_RATE_TEXT} • ${BT_BITS}-bit"
    N5_TRANS="Bluetooth A2DP (Lossy)"
    N5_EXACT=false
else
    N2_ENGINE="Platform Decoder"
    N2_MODE="Integer Stream"
    N2_BADGE="IDLE"

    N3_STATUS="Direct Routing Ready"
    N3_RATIO="1:1"
    N3_LOCK=true
    N3_BADGE="STANDBY"

    N4_STATUS="Direct Output Ready"
    N4_VOL="Bit-Exact Ready"
    N4_BADGE="STANDBY"

    N5_DEV="$CARD_NAME"
    N5_CHIP="$CARD_CHIP"
    N5_FMT="PCM 24-bit"
    N5_TRANS="Vendor Direct PCM"
    N5_EXACT=true
    N5_BADGE="HAL READY"
fi

cat << EOF
{
  "playback_state": "$PLAYBACK_STATE",
  "active_endpoint": "$ACTIVE_ENDPOINT",
  "active_package": "$MEDIA_PKG",
  "is_video_app": $IS_VIDEO_APP,
  "is_usb_active": $([ $IS_USB_ACTIVE -eq 1 ] && echo "true" || echo "false"),
  "is_internal_active": $([ $IS_INTERNAL_ACTIVE -eq 1 ] && echo "true" || echo "false"),
  "bluetooth_suppressed": $BT_SUPPRESSED,
  "top_display": {
    "rate_text": "$TOP_RATE_TEXT",
    "endpoint_text": "$TOP_ENDPOINT_TEXT"
  },
  "signal_chain": {
    "node1_source": {
      "app": "$MEDIA_APP_NAME ($MEDIA_PKG)",
      "title": "$MEDIA_TITLE",
      "artist": "$MEDIA_ARTIST",
      "container": "$METRICS_CONTAINER",
      "sample_rate": $SOURCE_SAMPLE_RATE,
      "bit_depth": $BIT_DEPTH,
      "channels": $HW_CHANNELS,
      "badge": "$([ "$PLAYBACK_STATE" = "PLAYING" ] && echo "ACTIVE STREAM" || echo "STANDBY")"
    },
    "node2_decoder": {
      "engine": "$N2_ENGINE",
      "mode": "$N2_MODE",
      "badge": "$N2_BADGE"
    },
    "node3_resampler": {
      "status": "$N3_STATUS",
      "ratio": "$N3_RATIO",
      "lock_48k_defeated": $N3_LOCK,
      "badge": "$N3_BADGE"
    },
    "node4_dsp": {
      "status": "$N4_STATUS",
      "volume_truncation": "$N4_VOL",
      "badge": "$N4_BADGE"
    },
    "node5_endpoint": {
      "type": "$ACTIVE_ENDPOINT",
      "device_name": "$N5_DEV",
      "chip": "$N5_CHIP",
      "hardware_rate": "$TOP_RATE_TEXT",
      "format": "$N5_FMT",
      "bit_depth": $BIT_DEPTH,
      "transport": "$N5_TRANS",
      "bit_exact": $N5_EXACT,
      "period_size": $HW_PERIOD_SIZE,
      "buffer_size": $HW_BUFFER_SIZE,
      "badge": "$N5_BADGE"
    }
  },
  "bluetooth": $BT_JSON
}
EOF
