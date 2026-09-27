package com.tillrecorder.agent

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

data class SegmentMeta(val startedAtMs: Long, val endedAtMs: Long)

sealed class UploadResult {
    data object Ok : UploadResult()
    data object Unauthorized : UploadResult()
    data class Failed(val detail: String) : UploadResult()
}

object ShopClient {
    private const val TAG = "TillRecorder"

    fun checkToken(settings: ShopSettings): Boolean {
        val base = SettingsStore.normalizeBaseUrl(settings.baseUrl) ?: return false
        return try {
            val conn = open(base, "/api/check-token", settings.token, "GET")
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (error: Exception) {
            Log.w(TAG, "Connection check failed", error)
            false
        }
    }

    /** Returns whether the website wants the front camera on, or null if the check failed. */
    fun heartbeat(settings: ShopSettings, recording: Boolean): Boolean? {
        val base = SettingsStore.normalizeBaseUrl(settings.baseUrl) ?: return null
        if (settings.token.isBlank() || settings.deviceName.isBlank()) return null
        return try {
            val body = JSONObject()
                .put("device_id", settings.deviceId)
                .put("device_name", settings.deviceName)
                .put("recording", recording)
                .toString()
                .toByteArray(Charsets.UTF_8)
            val conn = open(base, "/api/heartbeat", settings.token, "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            conn.disconnect()
            if (code !in 200..299) null else JSONObject(text).optBoolean("camera", false)
        } catch (error: Exception) {
            Log.w(TAG, "Heartbeat failed", error)
            null
        }
    }

    fun upload(settings: ShopSettings, meta: SegmentMeta, file: File): UploadResult {
        val base = SettingsStore.normalizeBaseUrl(settings.baseUrl)
            ?: return UploadResult.Failed("Cloudflare address is not valid")
        if (settings.token.isBlank()) return UploadResult.Unauthorized
        val boundary = "----till${System.currentTimeMillis()}"
        val fields = listOf(
            "device_id" to settings.deviceId,
            "device_name" to settings.deviceName.replace("[\r\n\"]".toRegex(), "").take(80),
            "started_at_ms" to meta.startedAtMs.toString(),
            "ended_at_ms" to meta.endedAtMs.toString(),
            "local_day" to java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date(meta.startedAtMs)),
        )
        return try {
            val conn = open(base, "/api/segments", settings.token, "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setChunkedStreamingMode(0)
            conn.outputStream.use { out ->
                for ((name, value) in fields) writeField(out, boundary, name, value)
                writeFile(out, boundary, file)
                out.write("--$boundary--\r\n".toByteArray(Charsets.US_ASCII))
            }
            val code = conn.responseCode
            val detail = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            conn.disconnect()
            when (code) {
                in 200..299 -> UploadResult.Ok
                401, 403 -> UploadResult.Unauthorized
                else -> UploadResult.Failed("Cloudflare answered $code $detail")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Upload failed", error)
            UploadResult.Failed(error.message ?: "Could not reach Cloudflare")
        }
    }

    fun uploadCamera(settings: ShopSettings, jpeg: ByteArray) {
        uploadJpeg(settings, jpeg, "/api/camera-frame")
    }

    fun uploadLive(settings: ShopSettings, jpeg: ByteArray) {
        uploadJpeg(settings, jpeg, "/api/live")
    }

    private fun uploadJpeg(settings: ShopSettings, jpeg: ByteArray, path: String) {
        val base = SettingsStore.normalizeBaseUrl(settings.baseUrl) ?: return
        if (settings.token.isBlank() || jpeg.isEmpty()) return
        try {
            val conn = open(base, path, settings.token, "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "image/jpeg")
            conn.setRequestProperty("X-Device-Id", settings.deviceId)
            conn.setRequestProperty("X-Device-Name", settings.deviceName.replace("[\r\n\"]".toRegex(), "").take(80))
            conn.setFixedLengthStreamingMode(jpeg.size)
            conn.outputStream.use { it.write(jpeg) }
            conn.responseCode
            conn.disconnect()
        } catch (error: Exception) {
            Log.w(TAG, "Live frame failed", error)
        }
    }

    private fun open(base: String, path: String, token: String, method: String): HttpURLConnection {
        val conn = (URL(base + path).openConnection() as HttpURLConnection)
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    private fun writeField(out: OutputStream, boundary: String, name: String, value: String) {
        out.write("--$boundary\r\n".toByteArray(Charsets.US_ASCII))
        out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
    }

    private fun writeFile(out: OutputStream, boundary: String, file: File) {
        out.write("--$boundary\r\n".toByteArray(Charsets.US_ASCII))
        out.write(
            "Content-Disposition: form-data; name=\"file\"; filename=\"segment.mp4\"\r\n".toByteArray(Charsets.US_ASCII)
        )
        out.write("Content-Type: video/mp4\r\n\r\n".toByteArray(Charsets.US_ASCII))
        file.inputStream().use { input -> input.copyTo(out) }
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
    }
}
