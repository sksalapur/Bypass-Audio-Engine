package com.music.bypass.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

object AncManager {

    private const val TAG = "AncManager"
    private const val PREFS_NAME = "realme_anc_prefs"
    private const val KEY_MODE = "active_mode"
    private const val KEY_BATTERY = "cached_battery"
    private const val KEY_BATTERY_LEFT = "cached_battery_left"
    private const val KEY_BATTERY_RIGHT = "cached_battery_right"
    private const val KEY_BATTERY_CASE = "cached_battery_case"
    private const val DEFAULT_MAC = "B0:38:E2:DF:F4:8C"

    // QuickBuds RFCOMM UUIDs for OnePlus / OPPO / realme earbuds
    private val UUID_FALLBACK = UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5")
    private val UUID_PRIMARY = UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5")

    const val MODE_ANC = "ANC"
    const val MODE_NORMAL = "NORMAL"
    const val MODE_TRANSPARENCY = "TRANSPARENCY"

    data class BudsInfo(
        val name: String,
        val mac: String,
        val battery: Int,
        val batteryLeft: Int = -1,
        val batteryRight: Int = -1,
        val batteryCase: Int = -1,
        val mode: String,
        val isConnected: Boolean
    )

    fun getActiveMode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MODE, MODE_ANC) ?: MODE_ANC
    }

    fun setActiveMode(context: Context, mode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MODE, mode).apply()
    }

    fun getCachedBattery(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_BATTERY, -1)
    }

    fun getCachedBatteryLeft(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_BATTERY_LEFT, -1)
    }

    fun getCachedBatteryRight(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_BATTERY_RIGHT, -1)
    }

    fun getCachedBatteryCase(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_BATTERY_CASE, -1)
    }

    fun setCachedBattery(context: Context, level: Int, left: Int = -1, right: Int = -1, case: Int = -1) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (level >= 0) editor.putInt(KEY_BATTERY, level)
        if (left >= 0) editor.putInt(KEY_BATTERY_LEFT, left)
        if (right >= 0) editor.putInt(KEY_BATTERY_RIGHT, right)
        if (case >= 0) editor.putInt(KEY_BATTERY_CASE, case)
        editor.apply()
    }

    @SuppressLint("MissingPermission")
    fun findBudsDevice(context: Context): BluetoothDevice? {
        return try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter() ?: return null
            val bonded = adapter.bondedDevices ?: return null

            bonded.firstOrNull { d ->
                val name = d.name?.lowercase() ?: ""
                val addr = d.address?.uppercase() ?: ""
                addr == DEFAULT_MAC || name.contains("t500") || (name.contains("realme") && name.contains("buds"))
            } ?: bonded.firstOrNull { d ->
                val name = d.name?.lowercase() ?: ""
                name.contains("buds") || name.contains("earbuds")
            } ?: try {
                adapter.getRemoteDevice(DEFAULT_MAC)
            } catch (_: Exception) { null }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up Bluetooth device: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun readBatteryFromBluetooth(device: BluetoothDevice?): Int {
        if (device == null) return -1
        try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            val lvl = method.invoke(device) as? Int
            if (lvl != null && lvl >= 0) return lvl
        } catch (_: Exception) {}

        try {
            val field = device.javaClass.getDeclaredField("mBatteryLevel")
            field.isAccessible = true
            val lvl = field.getInt(device)
            if (lvl >= 0) return lvl
        } catch (_: Exception) {}

        return -1
    }

    suspend fun getBudsStatus(context: Context): BudsInfo = withContext(Dispatchers.IO) {
        val device = findBudsDevice(context)
        val name = device?.let {
            try { it.name } catch (_: Exception) { null }
        } ?: "realme Buds T500 Pro"
        val mac = device?.address ?: DEFAULT_MAC

        // 1. Live RFCOMM cached battery takes highest precedence (it is the exact min(L, R) level)
        var battery = getCachedBattery(context)

        // 2. If no valid cache, try Bluetooth / dumpsys fallback
        if (battery < 0) {
            battery = readBatteryFromShell()
        }
        if (battery < 0) {
            battery = readBatteryFromBluetooth(device)
        }
        if (battery >= 0) {
            setCachedBattery(context, battery)
        }

        val mode = getActiveMode(context)
        val isConnected = battery >= 0 || isDeviceConnectedShell(mac) || (com.music.bypass.service.BudsService.instance?.isConnected == true)

        BudsInfo(
            name = name,
            mac = mac,
            battery = battery,
            batteryLeft = getCachedBatteryLeft(context),
            batteryRight = getCachedBatteryRight(context),
            batteryCase = getCachedBatteryCase(context),
            mode = mode,
            isConnected = isConnected
        )
    }

    /**
     * Connects directly to the earbuds via QuickBuds RFCOMM protocol and toggles ANC mode.
     */
    suspend fun switchMode(context: Context, mode: String): Boolean = withContext(Dispatchers.IO) {
        setActiveMode(context, mode)

        val device = findBudsDevice(context)
        val mac = device?.address ?: DEFAULT_MAC

        Log.i(TAG, "Initiating TWS ANC Switch to $mode for $mac")

        var success = false

        // 1. Direct QuickBuds RFCOMM protocol via BudsService (zero latency, reuses open socket)
        val service = com.music.bypass.service.BudsService.instance
        if (service != null && service.isConnected) {
            success = service.setMode(mode)
            Log.i(TAG, "Switched mode via active BudsService: success=$success")
            return@withContext success
        }

        // Start BudsService if not running
        com.music.bypass.service.BudsService.start(context)
        if (service != null) {
            success = service.setMode(mode)
            if (success) return@withContext true
        }

        // 2. Direct QuickBuds RFCOMM one-shot transmission fallback
        if (device != null) {
            try {
                success = sendRfcommCommand(context, device, mode)
                Log.i(TAG, "QuickBuds RFCOMM direct command result: success=$success")
            } catch (e: Throwable) {
                Log.w(TAG, "RFCOMM direct send failed: ${e.message}", e)
            }
        }

        // 3. Fallback: Root app_process invocation using anc_helper.dex (ShortcutsProvider IPC)
        if (!success) {
            val shortcutId = when (mode) {
                MODE_ANC -> "NOISE_REDUCTION_OPEN"
                MODE_TRANSPARENCY -> "NOISE_REDUCTION_TRANSPARENT"
                else -> "NOISE_REDUCTION_NORMAL"
            }
            Log.i(TAG, "Falling back to root anc_helper.dex (shortcutId=$shortcutId)")
            val cmd = "CLASSPATH=/data/local/tmp/anc_helper.dex app_process /system/bin com.music.bypass.AncCall $shortcutId $mac"
            val res = runRootCommand(cmd)
            Log.i(TAG, "Root helper result: $res")
            success = res != null && !res.contains("ANC_ERROR")
        }

        // 4. Fallback: In-process LSPosed Broadcast if active
        try {
            val lsposedIntent = Intent("com.music.bypass.SET_ANC_MODE").apply {
                setPackage("com.realme.link")
                putExtra("mode", mode)
                putExtra("mac", mac)
            }
            context.sendBroadcast(lsposedIntent)
        } catch (_: Exception) {}

        success
    }

    /**
     * Opens an RFCOMM BluetoothSocket to the earbud and sends the 0x0404 ANC packet.
     * Also queries battery status (0x0106) and updates cache.
     */
    @SuppressLint("MissingPermission")
    private fun sendRfcommCommand(context: Context, device: BluetoothDevice, mode: String): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()

        adapter?.cancelDiscovery()

        var socket: BluetoothSocket? = null

        // Try QuickBuds UUID sequence: 079A (fallback/primary on modern HeyMelody), 1107, then channel 15
        val candidates = listOf(
            UUID_FALLBACK to true,   // insecure 079A
            UUID_FALLBACK to false,  // secure 079A
            UUID_PRIMARY to true,    // insecure 1107
            UUID_PRIMARY to false    // secure 1107
        )

        for ((uuid, insecure) in candidates) {
            try {
                socket = if (insecure) {
                    device.createInsecureRfcommSocketToServiceRecord(uuid)
                } else {
                    device.createRfcommSocketToServiceRecord(uuid)
                }
                socket.connect()
                Log.d(TAG, "Connected to buds via UUID $uuid (insecure=$insecure)")
                break
            } catch (e: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
            }
        }

        if (socket == null) {
            // RFCOMM Channel 15 fallback
            try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                socket = m.invoke(device, 15) as BluetoothSocket
                socket.connect()
                Log.d(TAG, "Connected to buds via RFCOMM channel 15")
            } catch (e: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
            }
        }

        if (socket == null || !socket.isConnected) {
            Log.w(TAG, "Failed to connect RFCOMM socket to ${device.address}")
            return false
        }

        return try {
            val out = socket.outputStream
            val `in` = socket.inputStream

            // For realme Buds T500 Pro (from QuickBuds models.json id 066452):
            // Mode 5 (ANC): slot 3 (bit 3) -> 1 shl 3 = 0x08
            // Mode 1 (Off/Normal): slot 0 (bit 0) -> 1 shl 0 = 0x01
            // Mode 2 (Transparency): slot 1 (bit 1) -> 1 shl 1 = 0x02
            val bitVal: Byte = when (mode) {
                MODE_NORMAL -> 0x01.toByte()
                MODE_TRANSPARENCY -> 0x02.toByte()
                else -> 0x08.toByte()
            }

            // ANC Frame (Cmd=0x0404, Seq=1, PayLen=3, Payload=01 01 <bitVal>)
            // Frame: AA 0A 00 00 04 04 01 03 00 01 01 <bitVal>
            val ancFrame = byteArrayOf(
                0xAA.toByte(), 0x0A, 0x00, 0x00,
                0x04, 0x04, 0x01, 0x03, 0x00,
                0x01, 0x01, bitVal
            )

            out.write(ancFrame)
            out.flush()
            Log.i(TAG, "Sent ANC frame for $mode: bitVal=0x${Integer.toHexString(bitVal.toInt() and 0xFF)}")

            // Query Battery Frame (Cmd=0x0106, Seq=2, PayLen=0)
            // Frame: AA 07 00 00 06 01 02 00 00
            val battQuery = byteArrayOf(
                0xAA.toByte(), 0x07, 0x00, 0x00,
                0x06, 0x01, 0x02, 0x00, 0x00
            )
            out.write(battQuery)
            out.flush()

            // Read replies for up to 350ms
            val startTime = System.currentTimeMillis()
            val buf = ByteArray(256)
            while (System.currentTimeMillis() - startTime < 350) {
                if (`in`.available() > 0) {
                    val read = `in`.read(buf)
                    if (read >= 11 && buf[0] == 0xAA.toByte()) {
                        val cmd = (buf[4].toInt() and 0xFF) or ((buf[5].toInt() and 0xFF) shl 8)
                        if (cmd == 0x8106) {
                            // Battery response: status(00), count, [idx, raw]...
                            val count = buf[10].toInt() and 0xFF
                            var lLevel = -1
                            var rLevel = -1
                            for (j in 0 until count) {
                                val offset = 11 + j * 2
                                if (offset + 1 < read) {
                                    val idx = buf[offset].toInt() and 0xFF
                                    val raw = buf[offset + 1].toInt() and 0xFF
                                    val lvl = raw and 0x7F
                                    if (idx == 1) lLevel = lvl
                                    if (idx == 2) rLevel = lvl
                                }
                            }
                            val avgBatt = when {
                                lLevel > 0 && rLevel > 0 -> minOf(lLevel, rLevel)
                                lLevel > 0 -> lLevel
                                rLevel > 0 -> rLevel
                                else -> -1
                            }
                            if (avgBatt > 0) {
                                setCachedBattery(context, avgBatt, lLevel, rLevel)
                                Log.i(TAG, "Parsed live RFCOMM battery: $avgBatt% (L=$lLevel, R=$rLevel)")
                            }
                        }
                    }
                }
                Thread.sleep(30)
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error in RFCOMM transmission: ${e.message}", e)
            false
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun readBatteryFromShell(): Int {
        // First check telemetry cache file if it exists (0ms, 0 mutex locks)
        try {
            val f = java.io.File("/data/local/tmp/bypass_telemetry/bt_meta.env")
            if (f.exists() && f.canRead()) {
                val lines = f.readLines()
                for (line in lines) {
                    if (line.startsWith("BT_BATTERY=")) {
                        val v = line.substringAfter("=").trim().toIntOrNull()
                        if (v != null && v >= 0) return v
                    }
                }
            }
        } catch (_: Exception) {}
        return -1
    }

    private fun isDeviceConnectedShell(mac: String): Boolean {
        try {
            val f = java.io.File("/data/local/tmp/bypass_telemetry/bt_meta.env")
            if (f.exists() && f.canRead()) {
                return f.readText().contains(mac, ignoreCase = true)
            }
        } catch (_: Exception) {}
        return false
    }

    private fun runRootCommand(cmd: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            p.waitFor()
            sb.toString().trim()
        } catch (_: Exception) {
            null
        }
    }
}
