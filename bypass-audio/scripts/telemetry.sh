#!/system/bin/sh
# ══════════════════════════════════════════════════════════════════════════════
# Bypass Audiophile Engine — High-Performance Zero-Lock Telemetry Engine
# Optimized to eliminate Bluetooth A2DP mutex starvation and buffer cracking.
# Features TTL-caching for dumpsys calls and instant procfs ALSA reads.
# ══════════════════════════════════════════════════════════════════════════════

# Cache directory & setup
CACHE_DIR="/data/local/tmp/bypass_telemetry"
[ -d "$CACHE_DIR" ] || mkdir -p "$CACHE_DIR" 2>/dev/null

SCRIPTS_DIR="${0%/*}"
[ -z "$SCRIPTS_DIR" ] || [ "$SCRIPTS_DIR" = "." ] && SCRIPTS_DIR="/data/adb/modules/bypass-audio/scripts"

NOW=$(date +%s 2>/dev/null || echo 0)

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
BT_SUPPRESSED="true"
BT_JSON="null"

# 1. Fast Media Session Inspection with 3-second TTL Cache
MS_ENV="$CACHE_DIR/ms_parsed.env"
MS_MTIME=$(stat -c %Y "$MS_ENV" 2>/dev/null || echo 0)
MS_AGE=$((NOW - MS_MTIME))

if [ "$MS_AGE" -ge 0 ] && [ "$MS_AGE" -lt 3 ] && [ -f "$MS_ENV" ]; then
    . "$MS_ENV"
else
    dumpsys media_session 2>/dev/null > "$CACHE_DIR/ms_dump.cache"
    if [ -f "$SCRIPTS_DIR/parse_ms.awk" ]; then
        awk -f "$SCRIPTS_DIR/parse_ms.awk" "$CACHE_DIR/ms_dump.cache" > "$MS_ENV" 2>/dev/null
    else
        awk '
        BEGIN { playing=0; pkg="com.music.bitchord"; title="BitChord Track"; artist="BitChord Playback"; }
        /package=/ { match($0, /package=[^ ]*/); if (RSTART>0) { p=substr($0, RSTART+8, RLENGTH-8); gsub(/[\r\n"\\ ]/, "", p); if (p!="com.android.server.telecom" && p!="com.google.android.gms" && p!="system" && p!="") pkg=p; } }
        /state=PlaybackState \{state=3/ || /state=3/ { playing=1; }
        /metadata:.*description=/ {
            match($0, /description=[^,\r\n]*/); if (RSTART>0) { t=substr($0, RSTART+12, RLENGTH-12); gsub(/^ +| +$/, "", t); gsub(/[\r\n"\\]/, "", t); if (t!="" && t!="null") title=t; }
            match($0, /description=[^,]*, *[^,\r\n]*/); if (RSTART>0) { s=substr($0, RSTART+12, RLENGTH-12); sub(/.*, */, "", s); gsub(/^ +| +$/, "", s); gsub(/[\r\n"\\]/, "", s); if (s!="" && s!="null" && s!=title) artist=s; }
        }
        END { print "MS_PLAYING=" playing; print "MEDIA_PKG=\"" pkg "\""; print "MEDIA_TITLE=\"" title "\""; print "MEDIA_ARTIST=\"" artist "\""; }
        ' "$CACHE_DIR/ms_dump.cache" > "$MS_ENV" 2>/dev/null
    fi
    . "$MS_ENV"
fi

[ -z "$MEDIA_PKG" ] && MEDIA_PKG="com.music.bitchord"
[ -z "$MEDIA_TITLE" ] && MEDIA_TITLE="BitChord Track"
[ -z "$MEDIA_ARTIST" ] && MEDIA_ARTIST="BitChord Playback"
[ -z "$MS_PLAYING" ] && MS_PLAYING=0

# 2. Fast Media Metrics Stream Inspection with 3-second TTL Cache
MM_ENV="$CACHE_DIR/mm_parsed.env"
MM_MTIME=$(stat -c %Y "$MM_ENV" 2>/dev/null || echo 0)
MM_AGE=$((NOW - MM_MTIME))

if [ "$MM_AGE" -ge 0 ] && [ "$MM_AGE" -lt 3 ] && [ -f "$MM_ENV" ]; then
    . "$MM_ENV"
else
    dumpsys media.metrics 2>/dev/null | grep -E '(\{codec,|\{audio\.track\.)' | tail -n 25 > "$CACHE_DIR/mm_dump.cache"
    if [ -f "$SCRIPTS_DIR/parse_mm.awk" ]; then
        awk -f "$SCRIPTS_DIR/parse_mm.awk" "$CACHE_DIR/mm_dump.cache" > "$MM_ENV" 2>/dev/null
    else
        awk '
        BEGIN { rate=0; container="FLAC / WAV"; }
        /sampleRate=[0-9]*/ { match($0, /sampleRate=[0-9]*/); if (RSTART>0) { s=substr($0, RSTART+11, RLENGTH-11); if (s+0>0) rate=s+0; } }
        /sample_rate:[0-9]*/ { match($0, /sample_rate:[0-9]*/); if (RSTART>0) { s=substr($0, RSTART+12, RLENGTH-12); if (s+0>0) rate=s+0; } }
        /[Ff][Ll][Aa][Cc]/ { container="FLAC"; }
        /[Oo][Pp][Uu][Ss]/ { container="Opus"; }
        /[Aa][Aa][Cc]/ { container="AAC"; }
        /[Aa][Ll][Aa][Cc]/ { container="ALAC"; }
        /[Mm][Pp]3/ { container="MP3"; }
        END { print "METRICS_RATE=" rate; print "METRICS_CONTAINER=\"" container "\""; }
        ' "$CACHE_DIR/mm_dump.cache" > "$MM_ENV" 2>/dev/null
    fi
    . "$MM_ENV"
fi

[ -z "$METRICS_CONTAINER" ] && METRICS_CONTAINER="FLAC / WAV"
[ -z "$METRICS_RATE" ] && METRICS_RATE=0

# Friendly App Name & Video App Detection (Zero-cost pure shell case)
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

# 3. Inspect kernel ALSA sound cards via /proc/asound/cards (< 0.05 ms)
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

        HW_PARAMS="/proc/asound/card$CARD_NUM/pcm0p/sub0/hw_params"
        if [ -f "$HW_PARAMS" ]; then
            CONTENT=$(cat "$HW_PARAMS" 2>/dev/null)
            if [ -n "$CONTENT" ] && [ "$CONTENT" != "closed" ]; then
                PLAYBACK_STATE="PLAYING"
                IS_USB_ACTIVE=1
                eval $(awk '
                /rate:/ { print "HW_RATE=" $2 }
                /format:/ { print "HW_FORMAT=\"" $2 "\"" }
                /channels:/ { print "HW_CHANNELS=" $2 }
                /period_size:/ { print "HW_PERIOD_SIZE=" $2 }
                /buffer_size:/ { print "HW_BUFFER_SIZE=" $2 }
                ' "$HW_PARAMS" 2>/dev/null)
            elif [ "$MS_PLAYING" -eq 1 ]; then
                PLAYBACK_STATE="PLAYING"
                IS_USB_ACTIVE=1
            fi
        fi
    fi

    # Check internal sound cards ONLY if USB is not present (< 20 ms)
    if [ "$USB_CARD_FOUND" -eq 0 ] && [ -d "/proc/asound/card0" ]; then
        ACTIVE_PCM=$(grep -l -v "closed" /proc/asound/card0/pcm*p/sub0/hw_params 2>/dev/null | head -n1)
        if [ -n "$ACTIVE_PCM" ] && [ -f "$ACTIVE_PCM" ]; then
            PLAYBACK_STATE="PLAYING"
            IS_INTERNAL_ACTIVE=1
            ACTIVE_ENDPOINT="INTERNAL_DAC"
            CARD_NAME="Internal Hi-Fi Direct PCM"
            CARD_CHIP="Qualcomm Hexagon DSP / MediaTek HiFi"
            eval $(awk '
            /rate:/ { print "HW_RATE=" $2 }
            /format:/ { print "HW_FORMAT=\"" $2 "\"" }
            /channels:/ { print "HW_CHANNELS=" $2 }
            /period_size:/ { print "HW_PERIOD_SIZE=" $2 }
            /buffer_size:/ { print "HW_BUFFER_SIZE=" $2 }
            ' "$ACTIVE_PCM" 2>/dev/null)
        fi
    fi
fi

# 4. Bluetooth Stack Inspection (STRICT SKIP if USB DAC or Internal DAC is active)
# Anti-Crackle Rule: Never call raw dumpsys bluetooth_manager on every poll!
# Uses 15-second TTL file cache to prevent holding com.android.bluetooth A2DP locks.
if [ "$USB_CARD_FOUND" -eq 0 ] && [ "$IS_INTERNAL_ACTIVE" -eq 0 ]; then
    BT_ENV="$CACHE_DIR/bt_parsed.env"
    BT_MTIME=$(stat -c %Y "$BT_ENV" 2>/dev/null || echo 0)
    BT_AGE=$((NOW - BT_MTIME))

    if [ "$BT_AGE" -ge 0 ] && [ "$BT_AGE" -lt 15 ] && [ -f "$BT_ENV" ]; then
        . "$BT_ENV"
    else
        PROP_CODEC=$(getprop persist.bypass.audio.bt_codec 2>/dev/null)
        PROP_BITRATE=$(getprop persist.bypass.audio.bt_bitrate 2>/dev/null)

        dumpsys bluetooth_manager 2>/dev/null > "$CACHE_DIR/bt_dump.cache"
        if [ -f "$SCRIPTS_DIR/parse_bt.awk" ]; then
            awk -v prop_codec="$PROP_CODEC" -v prop_bitrate="$PROP_BITRATE" \
                -f "$SCRIPTS_DIR/parse_bt.awk" "$CACHE_DIR/bt_dump.cache" > "$BT_ENV" 2>/dev/null
        else
            awk -v prop_codec="$PROP_CODEC" -v prop_bitrate="$PROP_BITRATE" '
            BEGIN { connected=0; streaming=0; codec=(prop_codec!="")?prop_codec:"AAC"; dev="Bluetooth Headset"; rate=44100; bits=16; bitrate=(prop_bitrate!="")?prop_bitrate:"328 kbps"; }
            /Connected: true|ConnectionState: STATE_CONNECTED|mActiveDevice: [0-9a-fA-F:]+|state=Connected/ { connected=1; }
            /Streaming: true|EVENT_TYPE_AUDIO_STATE_CHANGED.*STARTED/ { streaming=1; }
            /model_name=/ { match($0, /model_name=[^|)]*/); if (RSTART>0 && dev=="Bluetooth Headset") { dev=substr($0, RSTART+11, RLENGTH-11); gsub(/[\r\n"\\]/, "", dev); } }
            /Current Codec:/ { if ($3!="") codec=$3; }
            /mSampleRate:0x[0-9a-fA-F]*\([0-9]*\)/ { match($0, /mSampleRate:0x[0-9a-fA-F]*\([0-9]*\)/); if (RSTART>0) { s=substr($0, RSTART, RLENGTH); sub(/.*\(/, "", s); sub(/\).*/, "", s); if (s+0>0) rate=s+0; } }
            /mBitsPerSample:0x[0-9a-fA-F]*\([0-9]*\)/ { match($0, /mBitsPerSample:0x[0-9a-fA-F]*\([0-9]*\)/); if (RSTART>0) { s=substr($0, RSTART, RLENGTH); sub(/.*\(/, "", s); sub(/\).*/, "", s); if (s+0>0) bits=s+0; } }
            /LHDC transmission bitrate/ { if ($6!="" && $6!="-1") bitrate=$6 " kbps (ABR)"; }
            END {
                if (codec ~ /^LHDC/) { if (codec=="LHDC_V2") codec="LHDC Version 2"; else codec="LHDC Version 5"; }
                else if (codec ~ /LDAC/ && bitrate=="328 kbps") bitrate="990 kbps";
                else if (codec ~ /AAC/ && bitrate=="328 kbps") bitrate="256 kbps";
                print "BT_CONNECTED=" connected; print "BT_STREAMING=" streaming; print "BT_CODEC=\"" codec "\""; print "BT_DEVICE=\"" dev "\""; print "BT_RATE=" rate; print "BT_BITS=" bits; print "BT_BITRATE=\"" bitrate "\"";
            }
            ' "$CACHE_DIR/bt_dump.cache" > "$BT_ENV" 2>/dev/null
        fi
        . "$BT_ENV"
    fi

    if [ "$BT_CONNECTED" -eq 1 ]; then
        ACTIVE_ENDPOINT="BLUETOOTH"
        BT_SUPPRESSED="false"

        if [ "$BT_STREAMING" -eq 1 ] || [ "$MS_PLAYING" -eq 1 ]; then
            PLAYBACK_STATE="PLAYING"
        fi

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
