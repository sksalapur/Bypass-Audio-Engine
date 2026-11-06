#!/system/bin/sh
# ═══════════════════════════════════════════════════════════════════
# Bypass Audiophile Engine — Magisk / APatch Late-Start Service
# ═══════════════════════════════════════════════════════════════════

MODDIR="${0%/*}"

# Wait until Android has completely booted
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done

# Enforce Critical Anti-Crackle Rule:
# Keep super_hifi=0 so Full-Speed DACs (KT0210 Chu II DSP) don't drop packets
resetprop vendor.audio.usb.super_hifi 0 2>/dev/null || setprop vendor.audio.usb.super_hifi 0 2>/dev/null || true

# Grant open traversal and read/write permissions to all USB device nodes
chmod -R a+rwX /dev/bus/usb 2>/dev/null || true
chmod -R 777 /dev/bus/usb 2>/dev/null || true

# Initialize BitChord settings directory and default configuration
if [ -d "/data/data/com.music.bitchord" ]; then
    /system/bin/sh "$MODDIR/scripts/controls.sh" init >/dev/null 2>&1 || true
fi

# Kill any lingering instances of port 9090 listener
pkill -9 -f "9090 -L" 2>/dev/null || true
pkill -9 -f "$MODDIR/scripts/server.sh" 2>/dev/null || true

# Start WebUI daemon and background supervisor
(
    while true; do
        # Re-apply USB permissions in case DAC was hot-plugged (keep +x for directories)
        chmod -R a+rwX /dev/bus/usb 2>/dev/null || true
        chmod -R 777 /dev/bus/usb 2>/dev/null || true

        # Ensure server process is alive
        if ! pgrep -f "9090 -L" >/dev/null 2>&1; then
            nohup toybox nc -s 127.0.0.1 -p 9090 -L /system/bin/sh "$MODDIR/scripts/server.sh" </dev/null >/dev/null 2>&1 &
        fi
        sleep 5
    done
) </dev/null >/dev/null 2>&1 &

exit 0

