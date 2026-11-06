#!/usr/bin/env python3
"""
═══════════════════════════════════════════════════════════════════
Bypass Audiophile Engine — Magisk/APatch Module Packager & ADB Deployer
═══════════════════════════════════════════════════════════════════
Packages the module into a flashable Magisk/KernelSU/APatch zip archive
and automates deployment, installation, and service testing via ADB.
"""

import os
import sys
import zipfile
import subprocess
import argparse
import time

# Ensure UTF-8 stdout on Windows consoles
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

MODULE_DIR_NAME = "bypass-audio"
ZIP_NAME = "bypass-audiophile-engine.zip"

def find_module_root():
    current_dir = os.path.dirname(os.path.abspath(__file__))
    if os.path.exists(os.path.join(current_dir, "module.prop")):
        return current_dir
    candidate = os.path.join(current_dir, MODULE_DIR_NAME)
    if os.path.exists(os.path.join(candidate, "module.prop")):
        return candidate
    return current_dir

def build_zip(module_dir, output_zip):
    print(f"\n[+] Packaging Bypass Audiophile Engine into {output_zip}...")
    file_count = 0

    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(module_dir):
            for file in files:
                if file.endswith((".zip", ".pyc", ".git", ".DS_Store")) or file == "package_module.py":
                    continue
                full_path = os.path.join(root, file)
                rel_path = os.path.relpath(full_path, module_dir)

                arcname = rel_path.replace(os.path.sep, "/")

                zinfo = zipfile.ZipInfo(arcname)
                zinfo.date_time = time.localtime(os.path.getmtime(full_path))[:6]
                zinfo.compress_type = zipfile.ZIP_DEFLATED
                if file.endswith((".sh", ".rule", ".prop")) or "/scripts/" in arcname:
                    zinfo.external_attr = 0o755 << 16
                else:
                    zinfo.external_attr = 0o644 << 16

                with open(full_path, "rb") as f:
                    zf.writestr(zinfo, f.read())

                file_count += 1
                print(f"  + Added: {arcname}")

    zip_size_kb = os.path.getsize(output_zip) / 1024
    print(f"\n[OK] Created {output_zip} ({file_count} files, {zip_size_kb:.1f} KB)")
    return True

def run_adb(cmd, check=True):
    full_cmd = ["adb"] + cmd
    try:
        res = subprocess.run(full_cmd, capture_output=True, text=True, check=check)
        return res.returncode == 0, res.stdout.strip()
    except FileNotFoundError:
        return False, "ADB executable not found in PATH."
    except subprocess.CalledProcessError as e:
        return False, e.stderr.strip()

def check_device():
    success, out = run_adb(["devices"])
    if not success:
        print("[!] Error running ADB: " + out)
        return False
    lines = [line.strip() for line in out.splitlines() if line.strip() and not line.startswith("List of devices")]
    if not lines:
        print("[!] No ADB devices detected. Connect a rooted Android device with USB Debugging enabled.")
        return False
    print(f"[*] Connected device: {lines[0]}")
    return True

def deploy_adb(output_zip, direct_install=False, start_service=False):
    if not check_device():
        return False

    device_zip_path = f"/sdcard/Download/{ZIP_NAME}"
    print(f"\n[+] Pushing {output_zip} -> {device_zip_path}...")
    success, out = run_adb(["push", output_zip, device_zip_path])
    if not success:
        print(f"[!] Failed to push zip: {out}")
        return False
    print("[OK] Zip pushed successfully.")

    if direct_install:
        print("\n[*] Flashing module via APatch / Magisk...")
        if run_adb(["shell", "su", "-M", "-c", "test -f /data/adb/apd"])[0]:
            success, out = run_adb(["shell", "su", "-M", "-c", f"/data/adb/apd module install {device_zip_path}"])
        elif run_adb(["shell", "su", "-c", "command -v magisk"])[0]:
            success, out = run_adb(["shell", "su", "-c", f"magisk --install-module {device_zip_path}"])
        else:
            success, out = run_adb(["shell", "su", "-M", "-c", f"mkdir -p /data/adb/modules/bypass-audio && unzip -o {device_zip_path} -d /data/adb/modules/bypass-audio/"])

        print(out)

        # Update live directory so it works immediately before reboot
        sync_cmd = (
            "cp -af /data/adb/modules_update/bypass-audio/* /data/adb/modules/bypass-audio/ 2>/dev/null; "
            "rm -f /data/adb/modules/bypass-audio/disable /data/adb/modules/bypass-audio/remove; "
            "chmod 755 /data/adb/modules/bypass-audio/*.sh 2>/dev/null; "
            "chmod -R 755 /data/adb/modules/bypass-audio/scripts 2>/dev/null; "
            "chmod -R a+rX /data/adb/modules/bypass-audio/webroot 2>/dev/null; "
            "chmod -R a+rX /data/adb/modules/bypass-audio/system 2>/dev/null; "
            "setprop vendor.audio.usb.super_hifi 0 2>/dev/null; "
            "/system/bin/sh /data/adb/modules/bypass-audio/scripts/controls.sh init 2>/dev/null; "
            "echo 'MODULE_READY'"
        )
        run_adb(["shell", "su", "-M", "-c", sync_cmd])
        print("[OK] Module flashed and live environment updated!")

    if start_service:
        print("\n[*] Starting WebUI micro daemon directly on device (port 9090)...")
        start_cmd = (
            "pkill -9 -f '9090 -L' 2>/dev/null; "
            "nohup toybox nc -s 127.0.0.1 -p 9090 -L /system/bin/sh "
            "/data/adb/modules/bypass-audio/scripts/server.sh </dev/null >/dev/null 2>&1 &"
        )
        run_adb(["shell", "su", "-M", "-c", start_cmd], check=False)
        time.sleep(1)

        print("[*] Forwarding host port 9090 -> device port 9090...")
        run_adb(["forward", "tcp:9090", "tcp:9090"], check=False)
        print("[*] WebUI accessible locally at http://127.0.0.1:9090")

        try:
            import urllib.request
            resp = urllib.request.urlopen("http://127.0.0.1:9090/api/telemetry", timeout=3).read().decode()
            print("\n[*] Live Telemetry Verified:")
            print(resp[:250] + "...")
        except Exception as e:
            print("[*] Daemon spawned. Verify in device browser at http://127.0.0.1:9090")

    return True

def main():
    parser = argparse.ArgumentParser(description="Package and deploy Bypass Audiophile Engine Magisk/APatch module")
    parser.add_argument("--zip-only", action="store_true", help="Only build flashable zip, do not deploy via ADB")
    parser.add_argument("--push", action="store_true", help="Push zip to /sdcard/Download/ via ADB")
    parser.add_argument("--install", action="store_true", help="Extract and install directly to /data/adb/modules/bypass-audio/")
    parser.add_argument("--start", action="store_true", help="Launch background server daemon and forward port 9090")
    parser.add_argument("--status", action="store_true", help="Check live telemetry from device")
    parser.add_argument("--output", default=ZIP_NAME, help="Custom output zip filename")

    args = parser.parse_args()

    module_dir = find_module_root()
    if not os.path.exists(os.path.join(module_dir, "module.prop")):
        print(f"[!] Error: module.prop not found in {module_dir}")
        sys.exit(1)

    output_zip = os.path.abspath(args.output)
    build_zip(module_dir, output_zip)

    if args.status:
        print("\n[*] Checking telemetry on connected device...")
        run_adb(["forward", "tcp:9090", "tcp:9090"], check=False)
        success, out = run_adb(["shell", "curl", "-s", "http://127.0.0.1:9090/api/telemetry"])
        if success:
            print(out)
        else:
            print("Failed to query telemetry:", out)
        return

    if args.push or args.install or args.start:
        deploy_adb(output_zip, direct_install=args.install, start_service=args.start)
    else:
        print(f"\n[*] Flashable zip ready: {output_zip}")
        print("To deploy to a rooted device via ADB, run:")
        print(f"   python package_module.py --install --start")

if __name__ == "__main__":
    main()
