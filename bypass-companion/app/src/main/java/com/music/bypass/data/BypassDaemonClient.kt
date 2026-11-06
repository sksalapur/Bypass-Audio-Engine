package com.music.bypass.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object BypassDaemonClient {

    private const val BASE_URL = "http://127.0.0.1:9090"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(3000, TimeUnit.MILLISECONDS)
        .readTimeout(5000, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var lastValidTelemetry: TelemetryResponse? = null

    suspend fun getTelemetry(): TelemetryResponse? = withContext(Dispatchers.IO) {
        val result = try {
            val req = Request.Builder()
                .url("$BASE_URL/api/telemetry")
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) null
                else {
                    val body = res.body?.string()
                    if (body.isNullOrBlank()) null
                    else gson.fromJson(body, TelemetryResponse::class.java)
                }
            }
        } catch (e: Exception) {
            // Root shell fallback if HTTP daemon momentarily offline
            runRootScript("/data/adb/modules/bypass-audio/scripts/telemetry.sh")?.let { json ->
                try {
                    gson.fromJson(json, TelemetryResponse::class.java)
                } catch (_: Exception) { null }
            }
        }

        if (result != null) {
            lastValidTelemetry = result
            result
        } else {
            lastValidTelemetry
        }
    }

    suspend fun getControls(): ControlsConfig? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE_URL/api/controls")
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = res.body?.string() ?: return@withContext null
                gson.fromJson(body, ControlsConfig::class.java)
            }
        } catch (e: Exception) {
            runRootScript("/data/adb/modules/bypass-audio/scripts/controls.sh get")?.let { json ->
                try {
                    gson.fromJson(json, ControlsConfig::class.java)
                } catch (_: Exception) { null }
            }
        }
    }

    suspend fun setControl(key: String, value: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val emptyBody = "".toRequestBody("text/plain".toMediaType())
            val req = Request.Builder()
                .url("$BASE_URL/api/control?key=$key&value=$value")
                .post(emptyBody)
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            runRootScript("/data/adb/modules/bypass-audio/scripts/controls.sh set '$key' '$value'") != null
        }
    }

    suspend fun setVolume(level: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val emptyBody = "".toRequestBody("text/plain".toMediaType())
            val req = Request.Builder()
                .url("$BASE_URL/api/volume?level=$level")
                .post(emptyBody)
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            runRootScript("/data/adb/modules/bypass-audio/scripts/controls.sh set_volume '$level'") != null
        }
    }

    suspend fun restartAudioSubsystem(): Boolean = withContext(Dispatchers.IO) {
        try {
            val emptyBody = "".toRequestBody("text/plain".toMediaType())
            val req = Request.Builder()
                .url("$BASE_URL/api/restart_audio")
                .post(emptyBody)
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            runRootScript("/data/adb/modules/bypass-audio/scripts/controls.sh restart_audio") != null
        }
    }

    private fun runRootScript(cmd: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "/system/bin/sh $cmd"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val sb = java.lang.StringBuilder()
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

