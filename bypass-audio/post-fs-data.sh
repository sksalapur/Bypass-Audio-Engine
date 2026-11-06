#!/system/bin/sh
MODDIR="${0%/*}"
resetprop vendor.audio.usb.super_hifi 0 2>/dev/null || true
# Ensure directories have +x execute/traversal bit
find /dev/bus/usb -type d -exec chmod 755 {} + 2>/dev/null || true
find /dev/bus/usb -type c -exec chmod 666 {} + 2>/dev/null || true
