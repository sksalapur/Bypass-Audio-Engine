#!/system/bin/sh
# ═══════════════════════════════════════════════════════════════════
# Bypass Audiophile Engine — BitChord Hardware Controls & Settings Bridge
# Manages /data/data/com.music.bitchord/shared_prefs/bitchord_settings.xml
# ═══════════════════════════════════════════════════════════════════

BITCHORD_PKG="com.music.bitchord"
BITCHORD_DIR="/data/data/$BITCHORD_PKG"
SHARED_PREFS_DIR="$BITCHORD_DIR/shared_prefs"
SETTINGS_FILE="$SHARED_PREFS_DIR/bitchord_settings.xml"

# Dynamic BitChord UID discovery (fallback to 10003:10003)
get_bitchord_uid_gid() {
    if [ -d "$BITCHORD_DIR" ]; then
        # Try stat
        UG=$(stat -c "%u:%g" "$BITCHORD_DIR" 2>/dev/null)
        if [ -n "$UG" ]; then
            echo "$UG"
            return
        fi
        # Try ls -ld
        U=$(ls -ld "$BITCHORD_DIR" 2>/dev/null | awk '{print $3}')
        G=$(ls -ld "$BITCHORD_DIR" 2>/dev/null | awk '{print $4}')
        if [ -n "$U" ] && [ -n "$G" ]; then
            echo "${U}:${G}"
            return
        fi
    fi
    echo "10003:10003"
}

# Ensure directory and file exists with correct permissions
init_settings_file() {
    if [ ! -d "$SHARED_PREFS_DIR" ]; then
        mkdir -p "$SHARED_PREFS_DIR" 2>/dev/null || true
    fi

    if [ ! -f "$SETTINGS_FILE" ]; then
        cat << 'EOF' > "$SETTINGS_FILE"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="direct_bit_perfect_mode" value="true" />
    <boolean name="usb_hardware_volume" value="true" />
    <boolean name="internal_direct_output" value="true" />
    <boolean name="bit_perfect_rate_switching" value="true" />
    <string name="flac_replay_gain_mode">BIT_PERFECT_OFF</string>
    <boolean name="loudness_normalization" value="false" />
    <string name="audio_buffer_sizing">AUDIOPHILE_STABLE</string>
    <int name="hardware_volume_level" value="100" />
</map>
EOF
    fi

    UID_GID=$(get_bitchord_uid_gid)
    chown "$UID_GID" "$SETTINGS_FILE" 2>/dev/null || true
    chown "$UID_GID" "$SHARED_PREFS_DIR" 2>/dev/null || true
    chmod 660 "$SETTINGS_FILE" 2>/dev/null || true
    chmod 771 "$SHARED_PREFS_DIR" 2>/dev/null || true
}

# Read boolean value from XML
get_bool_key() {
    KEY="$1"
    DEFAULT="$2"
    if [ ! -f "$SETTINGS_FILE" ]; then
        echo "$DEFAULT"
        return
    fi
    VAL=$(grep "name=\"$KEY\"" "$SETTINGS_FILE" 2>/dev/null | sed -n 's/.*value="\([^"]*\)".*/\1/p' | tr -d '\r\n ')
    if [ "$VAL" = "true" ] || [ "$VAL" = "false" ]; then
        echo "$VAL"
    else
        echo "$DEFAULT"
    fi
}

# Read string value from XML
get_string_key() {
    KEY="$1"
    DEFAULT="$2"
    if [ ! -f "$SETTINGS_FILE" ]; then
        echo "$DEFAULT"
        return
    fi
    VAL=$(grep "name=\"$KEY\"" "$SETTINGS_FILE" 2>/dev/null | sed -n 's/.*<string name="'$KEY'">\([^<]*\)<\/string>.*/\1/p' | tr -d '\r\n ')
    if [ -n "$VAL" ]; then
        echo "$VAL"
    else
        echo "$DEFAULT"
    fi
}

# Read integer value from XML
get_int_key() {
    KEY="$1"
    DEFAULT="$2"
    if [ ! -f "$SETTINGS_FILE" ]; then
        echo "$DEFAULT"
        return
    fi
    VAL=$(grep "name=\"$KEY\"" "$SETTINGS_FILE" 2>/dev/null | sed -n 's/.*value="\([^"]*\)".*/\1/p' | tr -d '\r\n ')
    if [ -n "$VAL" ]; then
        echo "$VAL"
    else
        echo "$DEFAULT"
    fi
}

# Set boolean value in XML
set_bool_key() {
    KEY="$1"
    VAL="$2"
    init_settings_file
    if grep -q "name=\"$KEY\"" "$SETTINGS_FILE" 2>/dev/null; then
        sed -i "s|<boolean name=\"$KEY\" value=\"[^\"]*\" />|<boolean name=\"$KEY\" value=\"$VAL\" />|g" "$SETTINGS_FILE"
    else
        sed -i "s|</map>|    <boolean name=\"$KEY\" value=\"$VAL\" />\n</map>|g" "$SETTINGS_FILE"
    fi
    fix_permissions
}

# Set string value in XML
set_string_key() {
    KEY="$1"
    VAL="$2"
    init_settings_file
    if grep -q "name=\"$KEY\"" "$SETTINGS_FILE" 2>/dev/null; then
        sed -i "s|<string name=\"$KEY\">[^<]*</string>|<string name=\"$KEY\">$VAL</string>|g" "$SETTINGS_FILE"
    else
        sed -i "s|</map>|    <string name=\"$KEY\">$VAL</string>\n</map>|g" "$SETTINGS_FILE"
    fi
    fix_permissions
}

# Set int value in XML
set_int_key() {
    KEY="$1"
    VAL="$2"
    init_settings_file
    if grep -q "name=\"$KEY\"" "$SETTINGS_FILE" 2>/dev/null; then
        sed -i "s|<int name=\"$KEY\" value=\"[^\"]*\" />|<int name=\"$KEY\" value=\"$VAL\" />|g" "$SETTINGS_FILE"
    else
        sed -i "s|</map>|    <int name=\"$KEY\" value=\"$VAL\" />\n</map>|g" "$SETTINGS_FILE"
    fi
    fix_permissions
}

fix_permissions() {
    UID_GID=$(get_bitchord_uid_gid)
    chown "$UID_GID" "$SETTINGS_FILE" 2>/dev/null || true
    chmod 660 "$SETTINGS_FILE" 2>/dev/null || true
}

# Trigger dynamic reload intent in BitChord
notify_bitchord() {
    am broadcast -a com.music.bitchord.ACTION_SETTINGS_CHANGED >/dev/null 2>&1 || true
    am broadcast -a com.music.bitchord.RELOAD_AUDIO_SINK >/dev/null 2>&1 || true
    am startservice -a com.music.bitchord.ACTION_RELOAD_PIPELINE com.music.bitchord/.audio.AudioSinkService >/dev/null 2>&1 || true
}

# Apply hardware actions based on control settings
apply_hardware_control() {
    KEY="$1"
    VAL="$2"

    case "$KEY" in
        direct_bit_perfect_mode)
            if [ "$VAL" = "true" ]; then
                chmod -R a+rwX /dev/bus/usb 2>/dev/null || true
                chmod -R 777 /dev/bus/usb 2>/dev/null || true
            fi
            ;;
        flac_replay_gain_mode)
            # If Bit-Perfect Off, force loudness_normalization to false
            if [ "$VAL" = "BIT_PERFECT_OFF" ]; then
                set_bool_key "loudness_normalization" "false"
            else
                set_bool_key "loudness_normalization" "true"
            fi
            ;;
        audio_buffer_sizing)
            # Critical constraint: keep vendor.audio.usb.super_hifi=0
            setprop vendor.audio.usb.super_hifi 0 2>/dev/null || true
            ;;
    esac
}

# Find USB card number from /proc/asound/cards
get_usb_card_num() {
    if [ -f "/proc/asound/cards" ]; then
        CARD=$(grep -E '\[.*\]: USB-Audio' /proc/asound/cards 2>/dev/null | head -n1 | awk '{print $1}')
        if [ -n "$CARD" ]; then
            echo "$CARD"
            return
        fi
    fi
    echo "1"
}

# Set DAC hardware volume via ALSA mixer (UAC2 Feature Unit)
apply_hardware_volume() {
    LEVEL="$1"
    # Clamp 0-100
    if [ "$LEVEL" -lt 0 ] 2>/dev/null; then LEVEL=0; fi
    if [ "$LEVEL" -gt 100 ] 2>/dev/null; then LEVEL=100; fi

    CARD=$(get_usb_card_num)

    # In Android tinymix, flag is -D <card_num>
    if command -v tinymix >/dev/null 2>&1; then
        CTL_ID=$(tinymix -D "$CARD" 2>/dev/null | grep -i 'playback volume' | head -n1 | cut -f1 | tr -d ' ')
        if [ -n "$CTL_ID" ]; then
            tinymix -D "$CARD" "$CTL_ID" "$LEVEL" "$LEVEL" 2>/dev/null || \
            tinymix -D "$CARD" "$CTL_ID" "$LEVEL" 2>/dev/null || true
        else
            tinymix -D "$CARD" 5 "$LEVEL" "$LEVEL" 2>/dev/null || true
        fi
    fi

    # Try amixer as fallback
    if command -v amixer >/dev/null 2>&1; then
        amixer -c "$CARD" sset 'PCM' "${LEVEL}%" 2>/dev/null || \
        amixer -c "$CARD" sset 'Headphone' "${LEVEL}%" 2>/dev/null || true
    fi

    set_int_key "hardware_volume_level" "$LEVEL"
    notify_bitchord
}

# Return all current settings as JSON
output_settings_json() {
    init_settings_file
    DIRECT_BIT_PERFECT=$(get_bool_key "direct_bit_perfect_mode" "true")
    USB_HW_VOL=$(get_bool_key "usb_hardware_volume" "true")
    INTERNAL_DIRECT=$(get_bool_key "internal_direct_output" "true")
    BIT_PERFECT_RATE=$(get_bool_key "bit_perfect_rate_switching" "true")
    REPLAY_GAIN=$(get_string_key "flac_replay_gain_mode" "BIT_PERFECT_OFF")
    LOUDNESS_NORM=$(get_bool_key "loudness_normalization" "false")
    BUFFER_SIZING=$(get_string_key "audio_buffer_sizing" "AUDIOPHILE_STABLE")
    HW_VOL_LEVEL=$(get_int_key "hardware_volume_level" "100")

    cat << EOF
{
  "direct_bit_perfect_mode": $DIRECT_BIT_PERFECT,
  "usb_hardware_volume": $USB_HW_VOL,
  "internal_direct_output": $INTERNAL_DIRECT,
  "bit_perfect_rate_switching": $BIT_PERFECT_RATE,
  "flac_replay_gain_mode": "$REPLAY_GAIN",
  "loudness_normalization": $LOUDNESS_NORM,
  "audio_buffer_sizing": "$BUFFER_SIZING",
  "hardware_volume_level": $HW_VOL_LEVEL
}
EOF
}

# Restart Android AudioServer and rebind HAL
restart_audio_subsystem() {
    killall -9 audioserver 2>/dev/null || pkill -9 audioserver 2>/dev/null || true
    pkill -9 -f "android.hardware.audio" 2>/dev/null || true
    sleep 1
    chmod -R a+rwX /dev/bus/usb 2>/dev/null || true
    chmod -R 777 /dev/bus/usb 2>/dev/null || true
    notify_bitchord
    echo '{"success":true,"message":"Audioserver restarted and USB HAL pipeline reinitialized"}'
}

# Main command dispatcher
ACTION="$1"
case "$ACTION" in
    init)
        init_settings_file
        echo '{"success":true,"message":"Initialized bitchord_settings.xml"}'
        ;;
    get)
        output_settings_json
        ;;
    restart_audio|restart)
        restart_audio_subsystem
        ;;
    set)
        KEY="$2"
        VAL="$3"
        if [ -z "$KEY" ] || [ -z "$VAL" ]; then
            echo '{"success":false,"error":"Missing key or value"}'
            exit 1
        fi

        case "$KEY" in
            direct_bit_perfect_mode|usb_hardware_volume|internal_direct_output|bit_perfect_rate_switching|loudness_normalization)
                set_bool_key "$KEY" "$VAL"
                apply_hardware_control "$KEY" "$VAL"
                notify_bitchord
                ;;
            flac_replay_gain_mode|audio_buffer_sizing)
                set_string_key "$KEY" "$VAL"
                apply_hardware_control "$KEY" "$VAL"
                notify_bitchord
                ;;
            hardware_volume_level)
                apply_hardware_volume "$VAL"
                ;;
            *)
                echo '{"success":false,"error":"Unknown setting key"}'
                exit 1
                ;;
        esac
        echo "{\"success\":true,\"key\":\"$KEY\",\"value\":\"$VAL\"}"
        ;;
    set_volume)
        LEVEL="$2"
        apply_hardware_volume "$LEVEL"
        echo "{\"success\":true,\"hardware_volume_level\":$LEVEL}"
        ;;
    *)
        output_settings_json
        ;;
esac

