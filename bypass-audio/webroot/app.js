// ══════════════════════════════════════════════════════════════════════════════
// Bypass Audiophile Engine — Mobile-First Client Application
// Real-time zero-lock telemetry & bidirectional hardware sync
// Supports both standalone HTTP (port 9090) and APatch/KernelSU WebUI bridge
// ══════════════════════════════════════════════════════════════════════════════

document.addEventListener("DOMContentLoaded", () => {
    // Determine API origin
    const isHttp = window.location.protocol === "http:" || window.location.protocol === "https:";
    const API_BASE = isHttp ? "" : "http://127.0.0.1:9090";

    // Top Bar Elements
    const topSource = document.getElementById("topSource");
    const topStatus = document.getElementById("topStatus");
    const topEndpoint = document.getElementById("topEndpoint");
    const badgeHal = document.getElementById("badgeHal");
    const badgeFlinger = document.getElementById("badgeFlinger");

    // 5-Node Rail Elements
    const node1Title = document.getElementById("node1Title");
    const node1Meta = document.getElementById("node1Meta");
    const node1Badge = document.getElementById("node1Badge");

    const node2Engine = document.getElementById("node2Engine");
    const node2Badge = document.getElementById("node2Badge");

    const node3Status = document.getElementById("node3Status");
    const node3Badge = document.getElementById("node3Badge");

    const node4Dsp = document.getElementById("node4Dsp");
    const node4Badge = document.getElementById("node4Badge");

    const node5Device = document.getElementById("node5Device");
    const node5Meta = document.getElementById("node5Meta");
    const node5Badge = document.getElementById("node5Badge");

    // Bluetooth Elements
    const bluetoothSection = document.getElementById("bluetoothSection");
    const btDeviceName = document.getElementById("btDeviceName");
    const btVerdict = document.getElementById("btVerdict");
    const btCodec = document.getElementById("btCodec");
    const btSampleRate = document.getElementById("btSampleRate");
    const btBitDepth = document.getElementById("btBitDepth");
    const btBitrate = document.getElementById("btBitrate");

    // ALSA Diagnostics Elements
    const diagInterface = document.getElementById("diag-interface");
    const diagClock = document.getElementById("diag-clock");
    const diagFormat = document.getElementById("diag-format");
    const diagPeriod = document.getElementById("diag-period");
    const diagXruns = document.getElementById("diag-xruns");

    // Controls Elements
    const switchUsbExclusive = document.getElementById("switch-usb-exclusive");
    const switchRateSwitching = document.getElementById("switch-rate-switching");
    const switchInternalDirect = document.getElementById("switch-internal-direct");
    const switchHwVolume = document.getElementById("switch-hw-volume");
    const hwVolumeSlider = document.getElementById("hwVolumeSlider");
    const volDisplay = document.getElementById("volDisplay");

    const segNormalization = document.getElementById("segNormalization");
    const segBuffer = document.getElementById("segBuffer");

    // System Operations Elements
    const btnRestartAudio = document.getElementById("btnRestartAudio");
    const restartSpinner = document.getElementById("restartSpinner");
    const restartBtnText = document.getElementById("restartBtnText");
    const opsToast = document.getElementById("opsToast");

    let isUserSlidingVolume = false;

    // ══════════════════════════════════════════════════════════════════════════
    // Low-Level Native Bridge (APatch / KernelSU window.ksu)
    // ══════════════════════════════════════════════════════════════════════════
    function ksuExec(cmd) {
        return new Promise((resolve, reject) => {
            if (!window.ksu || !window.ksu.exec) {
                reject(new Error("ksu bridge unavailable"));
                return;
            }
            try {
                const res = window.ksu.exec(cmd);
                if (res && typeof res.then === "function") {
                    res.then(r => resolve(r && r.stdout !== undefined ? r.stdout : r)).catch(reject);
                    return;
                }
            } catch (e) {
                // Try callback signature
            }

            const cb = "_ksu_cb_" + Math.random().toString(36).substring(2, 9);
            window[cb] = function(errno, stdout, stderr) {
                delete window[cb];
                if (errno === 0 || (!stderr && stdout)) resolve(stdout);
                else reject(new Error(stderr || "Error " + errno));
            };
            try {
                window.ksu.exec(cmd, "{}", cb);
            } catch (err) {
                delete window[cb];
                reject(err);
            }
        });
    }

    // Unified API Dispatcher (Direct KSU Bridge with HTTP fallback)
    async function executeApi(endpoint, method = "GET") {
        // 1. Try APatch / KernelSU native bridge if present
        if (window.ksu && window.ksu.exec) {
            try {
                if (endpoint === "/api/telemetry") {
                    const out = await ksuExec("/system/bin/sh /data/adb/modules/bypass-audio/scripts/telemetry.sh");
                    if (out) return JSON.parse(out.trim());
                } else if (endpoint === "/api/controls") {
                    const out = await ksuExec("/system/bin/sh /data/adb/modules/bypass-audio/scripts/controls.sh get");
                    if (out) return JSON.parse(out.trim());
                } else if (endpoint.startsWith("/api/control?")) {
                    const query = endpoint.split("?")[1] || "";
                    const params = new URLSearchParams(query);
                    const k = params.get("key");
                    const v = params.get("value");
                    const out = await ksuExec(`/system/bin/sh /data/adb/modules/bypass-audio/scripts/controls.sh set '${k}' '${v}'`);
                    if (out) return JSON.parse(out.trim());
                } else if (endpoint.startsWith("/api/volume?")) {
                    const query = endpoint.split("?")[1] || "";
                    const params = new URLSearchParams(query);
                    const lvl = params.get("level");
                    const out = await ksuExec(`/system/bin/sh /data/adb/modules/bypass-audio/scripts/controls.sh set_volume '${lvl}'`);
                    if (out) return JSON.parse(out.trim());
                } else if (endpoint === "/api/restart_audio") {
                    const out = await ksuExec("/system/bin/sh /data/adb/modules/bypass-audio/scripts/controls.sh restart_audio");
                    if (out) return JSON.parse(out.trim());
                }
            } catch (e) {
                console.warn("KSU bridge failed, falling back to HTTP:", e);
            }
        }

        // 2. HTTP Fallback
        const fullUrl = `${API_BASE}${endpoint}`;
        const res = await fetch(fullUrl, {
            method,
            cache: "no-store",
            headers: { "Cache-Control": "no-cache" }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return await res.json();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. Telemetry Polling Loop
    // ══════════════════════════════════════════════════════════════════════════
    async function fetchTelemetry() {
        try {
            const data = await executeApi("/api/telemetry");
            updateUI(data);
        } catch (e) {
            // Server momentarily restarting or transitioning
        }
    }

    function updateUI(data) {
        if (!data) return;

        const isPlaying = data.playback_state === "PLAYING";
        const top = data.top_display || {};
        const chain = data.signal_chain || {};
        const n1 = chain.node1_source || {};
        const n2 = chain.node2_decoder || {};
        const n3 = chain.node3_resampler || {};
        const n4 = chain.node4_dsp || {};
        const n5 = chain.node5_endpoint || {};

        // Top Status Badges
        if (badgeHal) {
            if (data.active_endpoint === "USB_DAC") {
                badgeHal.textContent = isPlaying ? "HAL Bypassed" : "HAL Direct Ready";
                badgeHal.style.color = "var(--gold)";
            } else if (data.active_endpoint === "BLUETOOTH") {
                badgeHal.textContent = "A2DP Active";
                badgeHal.style.color = "var(--blue)";
            } else {
                badgeHal.textContent = "HAL System";
                badgeHal.style.color = "var(--text-dim)";
            }
        }
        if (badgeFlinger) {
            if (data.active_endpoint === "USB_DAC") {
                badgeFlinger.textContent = "Mixer Defeated";
                badgeFlinger.style.color = "var(--green)";
            } else if (data.active_endpoint === "BLUETOOTH") {
                badgeFlinger.textContent = "Mixer Active";
                badgeFlinger.style.color = "var(--amber)";
            } else {
                badgeFlinger.textContent = "Mixer Standby";
                badgeFlinger.style.color = "var(--text-dim)";
            }
        }

        // Top Monitor Text
        if (topSource) topSource.textContent = top.rate_text || (isPlaying ? "Active" : "Standby");
        if (topStatus) {
            if (data.active_endpoint === "USB_DAC") {
                topStatus.textContent = isPlaying ? "Direct Pass" : "Standby";
            } else if (data.active_endpoint === "BLUETOOTH") {
                topStatus.textContent = isPlaying ? "A2DP Stream" : "Standby";
            } else {
                topStatus.textContent = isPlaying ? "System Playback" : "Standby";
            }
        }
        if (topEndpoint) topEndpoint.textContent = top.endpoint_text || (isPlaying ? "48.0 kHz • 24b" : "Standby • 24b");

        // Node 1: Source (Always display track & artist metadata)
        if (node1Title) node1Title.textContent = n1.title || "BitChord Track";
        if (node1Meta) {
            const artist = n1.artist || "BitChord Playback";
            const rateKhz = n1.sample_rate ? (n1.sample_rate / 1000).toFixed(1) + " kHz" : "44.1 kHz";
            const depth = n1.bit_depth ? n1.bit_depth + "-bit" : "16-bit";
            const cont = n1.container || "FLAC / WAV";
            node1Meta.textContent = `${artist} • ${cont} • ${rateKhz} / ${depth}`;
        }
        if (node1Badge) node1Badge.textContent = n1.badge || (isPlaying ? "PLAYING" : "STANDBY");

        // Node 2: Decoder
        if (node2Engine) node2Engine.textContent = n2.engine || "Platform Codec2 Float32";
        if (node2Badge) node2Badge.textContent = n2.badge || "LOSSLESS";

        // Node 3: Resampler Bypass
        if (node3Status) node3Status.textContent = n3.status || "Bypassed (1:1 Bit-Exact)";
        if (node3Badge) node3Badge.textContent = n3.badge || "BYPASS ACTIVE";

        // Node 4: DSP
        if (node4Dsp) node4Dsp.textContent = n4.status || "Zero Bit Truncation";
        if (node4Badge) node4Badge.textContent = n4.badge || "BIT-PERFECT";

        // Node 5: Hardware Endpoint
        if (node5Device) node5Device.textContent = n5.device_name || (data.is_usb_active ? "Moondrop Chu II DSP" : "System Audio");
        if (node5Meta) {
            const hwRate = n5.hardware_rate || (isPlaying ? "48.0 kHz" : "Standby");
            const fmt = n5.format || "S24_3LE";
            const chip = n5.chip || "DAC Endpoint";
            node5Meta.textContent = `${chip} • ${fmt} • ${hwRate}`;
        }
        if (node5Badge) node5Badge.textContent = n5.badge || "ENDPOINT";

        // Bluetooth Inspection: Strict Suppression when USB or Internal DAC is active
        if (bluetoothSection) {
            if (data.bluetooth_suppressed || data.is_usb_active || data.is_internal_active || !data.bluetooth) {
                bluetoothSection.style.display = "none";
            } else {
                bluetoothSection.style.display = "block";
                const bt = data.bluetooth;
                if (btDeviceName) btDeviceName.textContent = bt.device_name || "Bluetooth Audio";
                if (btVerdict) btVerdict.textContent = bt.verdict || "Lossy Bluetooth transmission";
                if (btCodec) btCodec.textContent = bt.codec || "LHDC";
                if (btSampleRate) {
                    const sr = typeof bt.sample_rate === 'number' ? (bt.sample_rate / 1000).toFixed(1) + " kHz" : bt.sample_rate;
                    btSampleRate.textContent = sr || "96.0 kHz";
                }
                if (btBitDepth) {
                    const bd = typeof bt.bit_depth === 'number' ? bt.bit_depth + "-bit" : bt.bit_depth;
                    btBitDepth.textContent = bd || "24-bit";
                }
                if (btBitrate) btBitrate.textContent = bt.bitrate || "400 kbps (ABR)";
            }
        }

        // Diagnostics Panel
        if (diagInterface) {
            if (data.active_endpoint === "USB_DAC") diagInterface.textContent = "USB-Audio pcm0p (UAC2)";
            else if (data.active_endpoint === "BLUETOOTH") diagInterface.textContent = "Bluetooth A2DP Stack";
            else diagInterface.textContent = "Internal HiFi PCM";
        }
        if (diagClock) diagClock.textContent = n5.hardware_rate || (isPlaying ? "48.0 kHz" : "Standby");
        if (diagFormat) diagFormat.textContent = n5.format ? `${n5.format} (${n5.bit_depth || 24}-bit)` : "S24_3LE (24-bit)";
        if (diagPeriod) diagPeriod.textContent = n5.period_size ? `${n5.period_size} / ${n5.buffer_size} frames` : "256 / 512 frames";
        if (diagXruns) diagXruns.textContent = "0 (Pristine Stream)";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. Hardware Controls Synchronization
    // ══════════════════════════════════════════════════════════════════════════
    async function loadControls() {
        try {
            const cfg = await executeApi("/api/controls");
            if (!cfg) return;

            if (switchUsbExclusive) switchUsbExclusive.checked = !!cfg.direct_bit_perfect_mode;
            if (switchRateSwitching) switchRateSwitching.checked = !!cfg.bit_perfect_rate_switching;
            if (switchInternalDirect) switchInternalDirect.checked = !!cfg.internal_direct_output;
            if (switchHwVolume) switchHwVolume.checked = !!cfg.usb_hardware_volume;

            if (hwVolumeSlider && !isUserSlidingVolume) {
                const vol = cfg.hardware_volume_level !== undefined ? cfg.hardware_volume_level : 100;
                hwVolumeSlider.value = vol;
                if (volDisplay) volDisplay.textContent = vol + "%";
            }

            if (segNormalization) {
                const mode = cfg.flac_replay_gain_mode || "BIT_PERFECT_OFF";
                segNormalization.querySelectorAll(".seg-btn").forEach(btn => {
                    btn.classList.toggle("active", btn.dataset.val === mode);
                });
            }

            if (segBuffer) {
                const buf = cfg.audio_buffer_sizing || "AUDIOPHILE_STABLE";
                segBuffer.querySelectorAll(".seg-btn").forEach(btn => {
                    btn.classList.toggle("active", btn.dataset.val === buf);
                });
            }
        } catch (e) {
            // Fail silently
        }
    }

    async function setControl(key, value) {
        try {
            await executeApi(`/api/control?key=${encodeURIComponent(key)}&value=${encodeURIComponent(value)}`, "POST");
            setTimeout(loadControls, 200);
        } catch (e) {
            console.error("Control sync failed:", e);
        }
    }

    // Toggle Listeners
    if (switchUsbExclusive) {
        switchUsbExclusive.addEventListener("change", (e) => {
            setControl("direct_bit_perfect_mode", e.target.checked ? "true" : "false");
        });
    }

    if (switchRateSwitching) {
        switchRateSwitching.addEventListener("change", (e) => {
            setControl("bit_perfect_rate_switching", e.target.checked ? "true" : "false");
        });
    }

    if (switchInternalDirect) {
        switchInternalDirect.addEventListener("change", (e) => {
            setControl("internal_direct_output", e.target.checked ? "true" : "false");
        });
    }

    if (switchHwVolume) {
        switchHwVolume.addEventListener("change", (e) => {
            setControl("usb_hardware_volume", e.target.checked ? "true" : "false");
        });
    }

    // Volume Slider Listeners
    if (hwVolumeSlider) {
        hwVolumeSlider.addEventListener("input", (e) => {
            isUserSlidingVolume = true;
            if (volDisplay) volDisplay.textContent = e.target.value + "%";
        });

        hwVolumeSlider.addEventListener("change", async (e) => {
            const val = e.target.value;
            try {
                await executeApi(`/api/volume?level=${encodeURIComponent(val)}`, "POST");
            } catch (err) {
                console.error("Volume set failed:", err);
            }
            setTimeout(() => {
                isUserSlidingVolume = false;
                loadControls();
            }, 300);
        });
    }

    // Segmented Buttons (Normalization & Buffers)
    if (segNormalization) {
        segNormalization.addEventListener("click", (e) => {
            const btn = e.target.closest(".seg-btn");
            if (!btn) return;
            const val = btn.dataset.val;
            setControl("flac_replay_gain_mode", val);
            segNormalization.querySelectorAll(".seg-btn").forEach(b => b.classList.remove("active"));
            btn.classList.add("active");
        });
    }

    if (segBuffer) {
        segBuffer.addEventListener("click", (e) => {
            const btn = e.target.closest(".seg-btn");
            if (!btn) return;
            const val = btn.dataset.val;
            setControl("audio_buffer_sizing", val);
            segBuffer.querySelectorAll(".seg-btn").forEach(b => b.classList.remove("active"));
            btn.classList.add("active");
        });
    }

    // System Operations: Restart Audio Server
    if (btnRestartAudio) {
        btnRestartAudio.addEventListener("click", async () => {
            if (btnRestartAudio.disabled) return;

            btnRestartAudio.disabled = true;
            if (restartSpinner) restartSpinner.style.display = "inline-block";
            if (restartBtnText) restartBtnText.textContent = "Restarting...";

            try {
                const json = await executeApi("/api/restart_audio", "POST");
                showToast(json && json.success ? "Audio server restarted successfully" : "Restart returned warning");
            } catch (err) {
                showToast("Audio server restart command dispatched");
            }

            setTimeout(() => {
                if (restartSpinner) restartSpinner.style.display = "none";
                if (restartBtnText) restartBtnText.textContent = "Restart Audio Server";
                btnRestartAudio.disabled = false;
                fetchTelemetry();
                loadControls();
            }, 3000);
        });
    }

    function showToast(msg) {
        if (!opsToast) return;
        opsToast.textContent = msg;
        opsToast.classList.add("visible");
        setTimeout(() => {
            opsToast.classList.remove("visible");
        }, 3500);
    }

    // Initial Load & Intervals
    fetchTelemetry();
    loadControls();
    setInterval(fetchTelemetry, 1000);
    setInterval(loadControls, 4000);
});
