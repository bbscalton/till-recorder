package com.tillrecorder.agent

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

data class SegmentMeta(val startedAtMs: Long, val endedAtMs: Long, val kind: String = "screen")

data class RemoteControl(
    val camera: Boolean,
    val locked: Boolean,
    val lockSeq: Int,
    val overhead: String = "",
    val scan: Boolean = false,
)

data class RtcIce(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int)

data class RtcRoom(
    val session: String,
    val offerType: String,
    val offerSdp: String,
    val answerType: String,
    val answerSdp: String,
    val viewerIce: List<RtcIce>,
    val phoneIce: List<RtcIce>,
    val updatedAt: Long,
) {
    companion object {
        fun empty() = RtcRoom("", "", "", "", "", emptyList(), emptyList(), 0L)
    }
}

data class RtcBundle(val screen: RtcRoom, val camera: RtcRoom)

sealed class UploadResult {
    data object Ok : UploadResult()
    data object Unauthorized : UploadResult()
    data class Failed(val detail: String) : UploadResult()
}

object ShopClient {
    private const val TAG = "TillRecorder"

    fun claimPair(deviceId: String, deviceName: String, code: String): String? {
        val base = SettingsStore.DEFAULT_URL
        return try {
            val body = JSONObject()
                .put("device_id", deviceId)
                .put("device_name", deviceName.replace("[\r\n\"]".toRegex(), "").take(80))
                .put("code", code)
                .toString()
                .toByteArray(Charsets.UTF_8)
            val conn = open(base, "/api/pair", "", "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            conn.disconnect()
            if (status !in 200..299) null else JSONObject(text).optString("token").ifBlank { null }
        } catch (error: Exception) {
            Log.w(TAG, "Pairing failed", error)
            null
        }
    }

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

    /** Camera and register-lock flags from the watch page, or null if the check failed. */
    fun heartbeat(settings: ShopSettings, recording: Boolean): RemoteControl? {
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
            if (code !in 200..299) {
                null
            } else {
                val json = JSONObject(text)
                RemoteControl(
                    camera = json.optBoolean("camera", false),
                    locked = json.optBoolean("locked", false),
                    lockSeq = json.optInt("lockSeq", 0),
                    overhead = json.optString("overhead", ""),
                    scan = json.optBoolean("scan", false),
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "Heartbeat failed", error)
            null
        }
    }

    fun postInputs(settings: ShopSettings, entries: List<Pair<Long, String>>): Boolean {
        val base = SettingsStore.normalizeBaseUrl(settings.baseUrl) ?: return false
        if (settings.token.isBlank() || entries.isEmpty()) return false
        return try {
            val list = org.json.JSONArray()
            for ((at, text) in entries) {
                list.put(JSONObject().put("at", at).put("text", text))
            }
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            val body = JSONObject()
                .put("device_id", settings.deviceId)
                .put("device_name", settings.deviceName)
                .put("local_day", day)
                .put("entries", list)
                .toString()
                .toByteArray(Charsets.UTF_8)
            val conn = open(base, "/api/inputs", settings.token, "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (error: Exception) {
            Log.w(TAG, "Input upload failed", error)
            false
        }
    }

    fun setOverhead(settings: ShopSettings, attached: Boolean, label: String): Boolean {
        val base = SettingsStore.normalizeBaseUrl(settings.baseUrl) ?: return false
        if (settings.token.isBlank()) return false
        return try {
            val body = JSONObject()
                .put("device_id", settings.deviceId)
                .put("attached", attached)
                .put("label", label.trim())
                .toString()
                .toByteArray(Charsets.UTF_8)
            val conn = open(base, "/api/overhead", settings.token, "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (error: Exception) {
            Log.w(TAG, "Overhead save failed", error)
            false
        }
    }

    fun setLocked(settings: ShopSettings, locked: Boolean, lockSeq: Int?): Boolean {
        val base = SettingsStore.normalizeBaseUrl(settings.baseUrl) ?: return false
        if (settings.token.isBlank()) return false
        return try {
            val payload = JSONObject()
                .put("device_id", settings.deviceId)
                .put("locked", locked)
            if (lockSeq != null) payload.put("lock_seq", lockSeq)
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            val conn = open(base, "/api/lock", settings.token, "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (error: Exception) {
            Log.w(TAG, "Lock update failed", error)
            false
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
            "kind" to when (meta.kind) {
                "camera" -> "camera"
                "overhead" -> "overhead"
                else -> "screen"
            },
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

    fun readRtc(settings: ShopSettings): RtcBundle? {
        val base = SettingsStore.normalizeBaseUrl(settings.baseUrl) ?: return null
        if (settings.token.isBlank()) return null
        return try {
            val conn = open(base, "/api/rtc?device_id=${settings.deviceId}", settings.token, "GET")
            conn.connectTimeout = 4_000
            conn.readTimeout = 4_000
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            conn.disconnect()
            if (code !in 200..299) null else {
                val json = JSONObject(text)
                RtcBundle(
                    screen = parseRoom(json.optJSONObject("screen")),
                    camera = parseRoom(json.optJSONObject("camera")),
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "Live signaling read failed", error)
            null
        }
    }

    fun writeRtc(
        settings: ShopSettings,
        kind: String,
        session: String,
        answerType: String?,
        answerSdp: String?,
        ice: List<RtcIce>?,
    ): String {
        val base = SettingsStore.normalizeBaseUrl(settings.baseUrl) ?: return "fail"
        if (settings.token.isBlank() || session.isBlank()) return "fail"
        try {
            val payload = JSONObject()
                .put("device_id", settings.deviceId)
                .put("kind", kind)
                .put("role", "phone")
                .put("session", session)
            if (!answerType.isNullOrBlank() && !answerSdp.isNullOrBlank()) {
                payload.put("answer", JSONObject().put("type", answerType).put("sdp", answerSdp))
            }
            if (ice != null) {
                val array = org.json.JSONArray()
                for (item in ice) {
                    array.put(
                        JSONObject()
                            .put("candidate", item.candidate)
                            .put("sdpMid", item.sdpMid)
                            .put("sdpMLineIndex", item.sdpMLineIndex)
                    )
                }
                payload.put("ice", array)
            }
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            val conn = open(base, "/api/rtc", settings.token, "POST")
            conn.connectTimeout = 4_000
            conn.readTimeout = 4_000
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
            if (code !in 200..299) {
                Log.w(TAG, "Live signaling write HTTP $code")
                return "fail"
            }
            val json = JSONObject(text)
            return when {
                json.optBoolean("stale", false) -> "stale"
                json.optBoolean("ok", false) -> "ok"
                else -> "fail"
            }
        } catch (error: Exception) {
            Log.w(TAG, "Live signaling write failed", error)
            return "fail"
        }
    }

    private fun parseRoom(json: JSONObject?): RtcRoom {
        if (json == null) return RtcRoom.empty()
        val offer = json.optJSONObject("offer")
        val answer = json.optJSONObject("answer")
        return RtcRoom(
            session = json.optString("session"),
            offerType = offer?.optString("type").orEmpty(),
            offerSdp = offer?.optString("sdp").orEmpty(),
            answerType = answer?.optString("type").orEmpty(),
            answerSdp = answer?.optString("sdp").orEmpty(),
            viewerIce = parseIce(json.optJSONArray("viewerIce")),
            phoneIce = parseIce(json.optJSONArray("phoneIce")),
            updatedAt = json.optLong("updatedAt", 0L),
        )
    }

    private fun parseIce(array: org.json.JSONArray?): List<RtcIce> {
        if (array == null) return emptyList()
        val out = ArrayList<RtcIce>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val candidate = item.optString("candidate")
            if (candidate.isBlank()) continue
            val mid = if (item.isNull("sdpMid")) null else item.optString("sdpMid").ifBlank { null }
            out.add(RtcIce(candidate, mid, item.optInt("sdpMLineIndex", 0)))
        }
        return out
    }

    fun uploadCamera(settings: ShopSettings, jpeg: ByteArray) {
        uploadJpeg(settings, jpeg, "/api/camera-frame")
    }

    fun uploadLive(settings: ShopSettings, jpeg: ByteArray) {
        uploadJpeg(settings, jpeg, "/api/live")
    }

    fun uploadStream(settings: ShopSettings, kind: String, sequence: Int, bytes: ByteArray, codec: String): Boolean {
        val base = SettingsStore.normalizeBaseUrl(settings.baseUrl) ?: return false
        if (settings.token.isBlank() || bytes.isEmpty()) return false
        try {
            val conn = open(base, "/api/stream", settings.token, "POST")
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "video/mp4")
            conn.setRequestProperty("X-Device-Id", settings.deviceId)
            conn.setRequestProperty("X-Stream-Kind", kind)
            conn.setRequestProperty("X-Stream-Seq", sequence.toString())
            conn.setRequestProperty("X-Stream-Codec", codec.take(32))
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.setRequestProperty("Connection", "keep-alive")
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.use { it.readBytes() }
            if (code in 200..299) {
                Log.i(TAG, "Live $kind #$sequence ${bytes.size} bytes HTTP $code")
                return true
            }
            Log.w(TAG, "Live $kind #$sequence ${bytes.size} bytes HTTP $code")
            return false
        } catch (error: Exception) {
            Log.w(TAG, "Live video upload failed", error)
            return false
        }
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
        if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
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
