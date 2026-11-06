#!/system/bin/sh
# ═══════════════════════════════════════════════════════════════════
# Bypass Audiophile Engine — Magisk / KernelSU / APatch Installer
# ═══════════════════════════════════════════════════════════════════

ui_print "********************************************************"
ui_print "          [*] Bypass Audiophile Engine v1.0.0           "
ui_print "        BitChord Hardware Bridge & Audio HAL Direct     "
ui_print "********************************************************"

ui_print "- Installing audio policy configurations..."
ui_print "- Configuring SELinux rules for untrusted_app USB access..."
ui_print "- Injecting zero-lock ALSA procfs telemetry engine..."
ui_print "- Enforcing KT0210 anti-crackle profile (super_hifi=0)..."

# Set permissions
set_perm_recursive "$MODPATH/scripts" 0 0 0755 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm_recursive "$MODPATH/webroot" 0 0 0755 0644

# Setup bitchord settings file if BitChord is installed
if [ -d "/data/data/com.music.bitchord" ]; then
    ui_print "- Detected BitChord installation! Linking hardware state..."
    /system/bin/sh "$MODPATH/scripts/controls.sh" init >/dev/null 2>&1 || true
fi

ui_print "- WebUI will be active at http://127.0.0.1:9090 upon boot"
ui_print "- Installation complete! Reboot to activate."
ui_print "********************************************************"

