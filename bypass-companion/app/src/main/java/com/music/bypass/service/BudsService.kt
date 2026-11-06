package com.music.bypass.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.music.bypass.MainActivity
import com.music.bypass.R
import com.music.bypass.data.AncManager
import com.music.bypass.widget.AncWidgetProvider
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BudsService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    @Volatile
    var isConnected = false
        private set

    @Volatile
    private var isConnecting = false

    private var isScreenLocked = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenLocked = true
                    Log.d(TAG, "Screen off: closing RFCOMM socket to preserve buds battery")
                    disconnectSocket()
                }
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                    isScreenLocked = false
                    Log.d(TAG, "Screen on: resuming RFCOMM connection")
                    connectBuds()
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (isTargetDevice(dev)) {
                        Log.i(TAG, "Target buds connected via Bluetooth ACL: ${dev?.address}")
                        connectBuds()
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (isTargetDevice(dev)) {
                        Log.i(TAG, "Target buds disconnected via Bluetooth ACL")
                        disconnectSocket()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "BudsService onCreate")
        startForegroundNotification()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(screenReceiver, filter)

        connectBuds()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val action = intent.action
            if (action == ACTION_SET_MODE) {
                val mode = intent.getStringExtra(EXTRA_MODE) ?: AncManager.MODE_ANC
                setMode(mode)
            } else if (action == ACTION_CONNECT) {
                connectBuds()
            } else if (action == ACTION_REFRESH) {
                queryState()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        disconnectSocket()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isTargetDevice(dev: BluetoothDevice?): Boolean {
        if (dev == null) return false
        val addr = dev.address?.uppercase() ?: ""
        val name = dev.name?.lowercase() ?: ""
        return addr == TARGET_MAC || name.contains("t500") || (name.contains("realme") && name.contains("buds"))
    }

    @Synchronized
    fun connectBuds() {
        if (isConnected || isConnecting || isScreenLocked) return
        isConnecting = true

        serviceScope.launch {
            try {
                val device = AncManager.findBudsDevice(this@BudsService)
                if (device == null) {
                    Log.d(TAG, "Target buds device not found in bonded list")
                    return@launch
                }

                Log.i(TAG, "Connecting RFCOMM to ${device.address}...")
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                    ?: BluetoothAdapter.getDefaultAdapter()
                adapter?.cancelDiscovery()

                val candidates = listOf(
                    UUID_FALLBACK to true,
                    UUID_FALLBACK to false,
                    UUID_PRIMARY to true,
                    UUID_PRIMARY to false
                )

                var sock: BluetoothSocket? = null
                for ((uuid, insecure) in candidates) {
                    try {
                        sock = if (insecure) {
                            device.createInsecureRfcommSocketToServiceRecord(uuid)
                        } else {
                            device.createRfcommSocketToServiceRecord(uuid)
                        }
                        sock.connect()
                        Log.i(TAG, "Connected via UUID $uuid (insecure=$insecure)")
                        break
                    } catch (e: Exception) {
                        try { sock?.close() } catch (_: Exception) {}
                        sock = null
                    }
                }

                if (sock == null) {
                    try {
                        val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        sock = m.invoke(device, 15) as BluetoothSocket
                        sock.connect()
                        Log.i(TAG, "Connected via Channel 15")
                    } catch (_: Exception) {
                        try { sock?.close() } catch (_: Exception) {}
                        sock = null
                    }
                }

                if (sock == null || !sock.isConnected) {
                    Log.w(TAG, "Could not establish RFCOMM socket to ${device.address}")
                    return@launch
                }

                synchronized(this@BudsService) {
                    socket = sock
                    outputStream = sock.outputStream
                    inputStream = sock.inputStream
                    isConnected = true
                }

                // Query initial state on connect
                sendInitialQueries()

                // Start listening loop
                startReadLoop(sock)
            } finally {
                isConnecting = false
            }
        }
    }

    fun queryState() {
        serviceScope.launch {
            if (!isConnected && !isScreenLocked) {
                connectBuds()
                var waitCount = 0
                while (!isConnected && waitCount < 10) {
                    delay(50)
                    waitCount++
                }
            }
            if (isConnected) {
                Log.d(TAG, "Querying live earbuds state over RFCOMM")
                writeFrame(buildPacket(0x010C, byteArrayOf(0x01, 0x01)))
                delay(80)
                writeFrame(buildPacket(0x0106))
            }
        }
    }

    private var seqCounter = 1

    private fun buildPacket(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val seq = (seqCounter++) and 0xFF
        val payLen = payload.size
        val totalLen = 7 + payLen
        val head = byteArrayOf(
            0xAA.toByte(),
            totalLen.toByte(),
            0x00,
            0x00,
            (cmd and 0xFF).toByte(),
            ((cmd shr 8) and 0xFF).toByte(),
            seq.toByte(),
            (payLen and 0xFF).toByte(),
            ((payLen shr 8) and 0xFF).toByte()
        )
        return head + payload
    }

    private fun sendInitialQueries() {
        serviceScope.launch {
            try {
                delay(200)
                // 1. Handshake (0x0100)
                Log.d(TAG, "Sending Handshake (0x0100)")
                writeFrame(buildPacket(0x0100))

                delay(200)
                // 2. Register spontaneous notifications for Battery (1), Wearing (2), ANC (3)
                Log.d(TAG, "Sending Register Notifications (0x0205)")
                writeFrame(buildPacket(0x0205, byteArrayOf(0x03, 0x01, 0x02, 0x03)))

                delay(200)
                // 3. Query ANC Mode (0x010C) with payload [0x01, 0x01]
                Log.d(TAG, "Sending Query ANC Mode (0x010C)")
                writeFrame(buildPacket(0x010C, byteArrayOf(0x01, 0x01)))

                delay(200)
                // 4. Query Battery (0x0106)
                Log.d(TAG, "Sending Query Battery (0x0106)")
                writeFrame(buildPacket(0x0106))
            } catch (e: Exception) {
                Log.w(TAG, "sendInitialQueries error: ${e.message}")
            }
        }
    }

    private fun startReadLoop(sock: BluetoothSocket) {
        readJob?.cancel()
        readJob = serviceScope.launch {
            val inStream = sock.inputStream
            val buf = ByteArray(512)
            try {
                while (isActive && isConnected) {
                    val read = inStream.read(buf)
                    if (read < 0) {
                        Log.i(TAG, "Socket reached EOF (read=$read), exiting read loop")
                        break
                    }
                    if (read > 0) {
                        handleIncomingData(buf, read)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Read loop ended: ${e.message}")
            } finally {
                disconnectSocket()
            }
        }
    }

    private fun handleIncomingData(data: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            if (data[offset] != 0xAA.toByte()) {
                offset++
                continue
            }
            if (offset + 9 > length) break

            val totalLen = (data[offset + 1].toInt() and 0xFF) + 2
            if (offset + totalLen > length) break

            val cmd = (data[offset + 4].toInt() and 0xFF) or ((data[offset + 5].toInt() and 0xFF) shl 8)
            val payLen = (data[offset + 7].toInt() and 0xFF) or ((data[offset + 8].toInt() and 0xFF) shl 8)
            val payStart = offset + 9
            val payEnd = minOf(payStart + payLen, length)

            if (payEnd <= length) {
                val payload = data.copyOfRange(payStart, payEnd)
                parsePacket(cmd, payload)
            }
            offset += totalLen
        }
    }

    private fun parsePacket(cmd: Int, payload: ByteArray) {
        when (cmd) {
            // Active Report from Buds (Stem squeeze / gesture / wear / battery push)
            0x0204 -> {
                // Genuine ANC change event: 03 01 01 <val>
                if (payload.size >= 4 &&
                    (payload[0].toInt() and 0xFF) == 0x03 &&
                    (payload[1].toInt() and 0xFF) == 0x01 &&
                    (payload[2].toInt() and 0xFF) == 0x01) {
                    val raw = payload[3].toInt() and 0xFF
                    val mode = when {
                        (raw and 0x01) != 0 -> AncManager.MODE_NORMAL
                        (raw and 0x02) != 0 -> AncManager.MODE_TRANSPARENCY
                        else -> AncManager.MODE_ANC
                    }
                    Log.i(TAG, "Direct earbud gesture report: raw=0x${Integer.toHexString(raw)} -> mode=$mode")
                    AncManager.setActiveMode(this, mode)
                    AncWidgetProvider.notifyUpdate(this)
                    updateNotification(mode, AncManager.getCachedBattery(this))
                }
                // Battery push: 01 <count> [idx, raw]...
                else if (payload.size >= 4 && (payload[0].toInt() and 0xFF) == 0x01) {
                    parseBatteryPayload(payload, 1)
                }
            }
            // ANC Query Response (0x810C)
            0x810C -> {
                val (isEcho, raw) = when {
                    payload.size >= 4 && (payload[1].toInt() and 0xFF) == 0x01 && (payload[2].toInt() and 0xFF) == 0x01 -> {
                        true to (payload[3].toInt() and 0xFF)
                    }
                    payload.size >= 3 && (payload[0].toInt() and 0xFF) == 0x01 && (payload[1].toInt() and 0xFF) == 0x01 -> {
                        true to (payload[2].toInt() and 0xFF)
                    }
                    else -> false to -1
                }
                if (isEcho && raw >= 0 && Integer.bitCount(raw) <= 2) {
                    val mode = when {
                        (raw and 0x01) != 0 -> AncManager.MODE_NORMAL
                        (raw and 0x02) != 0 -> AncManager.MODE_TRANSPARENCY
                        else -> AncManager.MODE_ANC
                    }
                    Log.i(TAG, "Direct earbud ANC query reply: raw=0x${Integer.toHexString(raw)} -> mode=$mode")
                    AncManager.setActiveMode(this, mode)
                    AncWidgetProvider.notifyUpdate(this)
                    updateNotification(mode, AncManager.getCachedBattery(this))
                }
            }
            // Battery Query Response (0x8106)
            0x8106 -> {
                if (payload.size >= 2) {
                    parseBatteryPayload(payload, 1)
                }
            }
        }
    }

    private fun parseBatteryPayload(payload: ByteArray, startOffset: Int) {
        try {
            val count = payload[startOffset].toInt() and 0xFF
            var lLevel = -1
            var rLevel = -1
            for (j in 0 until count) {
                val idxPos = startOffset + 1 + j * 2
                if (idxPos + 1 < payload.size) {
                    val idx = payload[idxPos].toInt() and 0xFF
                    val raw = payload[idxPos + 1].toInt() and 0xFF
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
                Log.i(TAG, "Parsed live RFCOMM battery: $avgBatt% (L=$lLevel, R=$rLevel)")
                AncManager.setCachedBattery(this, avgBatt)
                AncWidgetProvider.notifyUpdate(this)
                updateNotification(AncManager.getActiveMode(this), avgBatt)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error parsing battery: ${e.message}")
        }
    }

    fun setMode(mode: String): Boolean {
        AncManager.setActiveMode(this, mode)
        AncWidgetProvider.notifyUpdate(this)
        updateNotification(mode, AncManager.getCachedBattery(this))

        val bitVal: Byte = when (mode) {
            AncManager.MODE_NORMAL -> 0x01
            AncManager.MODE_TRANSPARENCY -> 0x02
            else -> 0x08
        }
        val ancFrame = buildPacket(0x0404, byteArrayOf(0x01, 0x01, bitVal))

        return if (isConnected) {
            writeFrame(ancFrame)
            Log.i(TAG, "Sent mode switch $mode over live socket")
            true
        } else {
            serviceScope.launch {
                connectBuds()
                delay(300)
                writeFrame(ancFrame)
            }
            true
        }
    }

    private fun writeFrame(frame: ByteArray) {
        try {
            val out = outputStream ?: return
            out.write(frame)
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Error writing frame: ${e.message}")
            disconnectSocket()
        }
    }

    @Synchronized
    fun disconnectSocket() {
        isConnected = false
        readJob?.cancel()
        readJob = null
        try { outputStream?.close() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        outputStream = null
        inputStream = null
        socket = null
    }

    private fun startForegroundNotification() {
        val channelId = "Bypass_Buds_Service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "realme Buds Hardware Link",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = buildNotification(AncManager.getActiveMode(this), AncManager.getCachedBattery(this))
        try {
            startForeground(1001, notification)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground error: ${e.message}")
        }
    }

    private fun updateNotification(mode: String, battery: Int) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(1001, buildNotification(mode, battery))
        } catch (_: Exception) {}
    }

    private fun buildNotification(mode: String, battery: Int): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val battText = if (battery >= 0) " • 🔋 $battery%" else ""
        val modeText = when (mode) {
            AncManager.MODE_ANC -> "ANC Active"
            AncManager.MODE_TRANSPARENCY -> "Ambient Active"
            else -> "Normal (Off)"
        }
        return NotificationCompat.Builder(this, "Bypass_Buds_Service")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("realme Buds T500 Pro")
            .setContentText("$modeText$battText")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "BudsService"
        private const val TARGET_MAC = "B0:38:E2:DF:F4:8C"
        private val UUID_FALLBACK = UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5")
        private val UUID_PRIMARY = UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5")

        const val ACTION_SET_MODE = "com.music.bypass.service.ACTION_SET_MODE"
        const val ACTION_CONNECT = "com.music.bypass.service.ACTION_CONNECT"
        const val ACTION_REFRESH = "com.music.bypass.service.ACTION_REFRESH"
        const val EXTRA_MODE = "extra_mode"

        @Volatile
        var instance: BudsService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, BudsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(intent)
                } catch (_: Exception) {
                    context.startService(intent)
                }
            } else {
                context.startService(intent)
            }
        }

        fun refresh(context: Context) {
            val intent = Intent(context, BudsService::class.java).apply {
                action = ACTION_REFRESH
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(intent)
                } catch (_: Exception) {
                    context.startService(intent)
                }
            } else {
                context.startService(intent)
            }
        }
    }
}
