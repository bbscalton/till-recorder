package com.tillrecorder.agent

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** What went wrong talking to the camera, in words a store owner can act on. Never contains the password. */
class CameraException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { UNREACHABLE, AUTH, NOT_FOUND, CODEC, PROTOCOL }
}

/**
 * Minimal RTSP client: DESCRIBE / SETUP (RTP over TCP interleaved) / PLAY with Basic or Digest login.
 * The request lines never carry the username or password.
 */
class RtspClient(bareUrl: String, private val user: String, private val password: String) : Closeable {
    data class Response(val status: Int, val reason: String, val headers: List<Pair<String, String>>, val body: String) {
        fun header(name: String): String = headers.firstOrNull { it.first.equals(name, true) }?.second.orEmpty()
        fun all(name: String): List<String> = headers.filter { it.first.equals(name, true) }.map { it.second }
    }

    data class Track(
        val codec: String,
        val payloadType: Int,
        val control: String,
        val playUrl: String,
        val sps: ByteArray?,
        val pps: ByteArray?,
    )

    val url: String = bareUrl.trim().replace("&amp;", "&")
    private val parsed = CameraAddress.parse(url)
        ?: throw CameraException(CameraException.Kind.PROTOCOL, CameraAddress.BAD_ADDRESS)
    val where: String = parsed.hostText() + ":" + (if (parsed.port > 0) parsed.port else 554)
    private val socket = Socket()
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var cseq = 1
    private var challenge: Map<String, String>? = null
    private var scheme = ""
    private var nonceCount = 0
    private var session = ""
    var sessionTimeoutSec = 60
        private set
    var videoChannel = 0
        private set

    init {
        if (parsed.scheme != "rtsp") {
            throw CameraException(CameraException.Kind.PROTOCOL, "Only rtsp:// addresses can be played.")
        }
    }

    fun connect(connectMs: Int = 8_000, readMs: Int = 10_000) {
        try {
            socket.connect(InetSocketAddress(parsed.host, if (parsed.port > 0) parsed.port else 554), connectMs)
            socket.soTimeout = readMs
            socket.tcpNoDelay = true
        } catch (error: UnknownHostException) {
            throw CameraException(CameraException.Kind.UNREACHABLE, "The camera name ${parsed.host} could not be found on this network.")
        } catch (error: SocketTimeoutException) {
            throw CameraException(CameraException.Kind.UNREACHABLE, "The camera at $where did not answer (timed out). Check the IP address and that the camera is on this network.")
        } catch (error: ConnectException) {
            throw CameraException(CameraException.Kind.UNREACHABLE, "The camera at $where refused the RTSP connection. Check the RTSP port (usually 554) and that RTSP is turned on in the camera.")
        } catch (error: NoRouteToHostException) {
            throw CameraException(CameraException.Kind.UNREACHABLE, "There is no route to the camera at $where from this register.")
        } catch (error: java.io.IOException) {
            throw CameraException(CameraException.Kind.UNREACHABLE, "Could not connect to the camera at $where.")
        }
        input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
        output = socket.getOutputStream()
    }

    fun describe(): Track {
        val response = request("DESCRIBE", url, listOf("Accept" to "application/sdp"))
        if (response.status == 404) {
            throw CameraException(CameraException.Kind.NOT_FOUND, "The camera at $where has no stream at that path (RTSP 404). Check the path after the port.")
        }
        expectOk(response, "DESCRIBE")
        val base = response.header("Content-Base").ifEmpty { response.header("Content-Location") }.ifEmpty { url }
        return parseSdp(response.body, base)
    }

    fun setup(track: Track) {
        val extra = mutableListOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1")
        val response = request("SETUP", track.control, extra)
        if (response.status == 461) {
            throw CameraException(CameraException.Kind.PROTOCOL, "The camera at $where refused RTP over TCP (RTSP 461). Turn on RTSP over TCP in the camera, or use another stream.")
        }
        expectOk(response, "SETUP")
        val sessionHeader = response.header("Session")
        session = sessionHeader.substringBefore(';').trim()
        Regex("timeout=(\\d+)").find(sessionHeader)?.groupValues?.get(1)?.toIntOrNull()?.let {
            if (it in 5..3600) sessionTimeoutSec = it
        }
        if (session.isEmpty()) throw CameraException(CameraException.Kind.PROTOCOL, "The camera at $where did not start an RTSP session.")
        Regex("interleaved=(\\d+)").find(response.header("Transport"))?.groupValues?.get(1)?.toIntOrNull()?.let { videoChannel = it }
    }

    fun play(track: Track) {
        val response = request("PLAY", track.playUrl, listOf("Session" to session, "Range" to "npt=0.000-"))
        expectOk(response, "PLAY")
    }

    /** Sends a keep-alive without waiting; the reply is skipped by [readPacket]. */
    fun keepAlive() {
        send("OPTIONS", url, listOf("Session" to session))
    }

    /** Next interleaved packet as (channel, payload); RTSP replies in between are skipped. Null at end of stream. */
    fun readPacket(): Pair<Int, ByteArray>? {
        while (true) {
            val lead = input.read()
            if (lead < 0) return null
            if (lead == 'R'.code) {
                readResponseRest("R")
                continue
            }
            if (lead != 0x24) continue
            val channel = input.read()
            val hi = input.read()
            val lo = input.read()
            if (channel < 0 || hi < 0 || lo < 0) return null
            val length = (hi shl 8) or lo
            val payload = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val n = input.read(payload, filled, length - filled)
                if (n <= 0) return null
                filled += n
            }
            return channel to payload
        }
    }

    override fun close() {
        try {
            if (session.isNotEmpty()) send("TEARDOWN", url, listOf("Session" to session))
        } catch (_: Exception) {
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun expectOk(response: Response, step: String) {
        if (response.status in 200..299) return
        throw CameraException(
            CameraException.Kind.PROTOCOL,
            "The camera at $where answered $step with RTSP ${response.status} ${response.reason}".trim() + ".",
        )
    }

    private fun request(method: String, target: String, extra: List<Pair<String, String>>): Response {
        var response = exchange(method, target, extra)
        var tries = 0
        while (response.status == 401 && tries < 2) {
            if (user.isEmpty()) {
                throw CameraException(CameraException.Kind.AUTH, "The camera at $where needs a username and password.")
            }
            val previous = challenge
            val next = pickChallenge(response.all("WWW-Authenticate"))
                ?: throw CameraException(CameraException.Kind.AUTH, "The camera at $where asked for a login type this app does not support.")
            val stale = next["stale"].equals("true", true)
            if (previous != null && !stale && tries > 0) break
            challenge = next
            scheme = next["#scheme"].orEmpty()
            nonceCount = 0
            tries += 1
            response = exchange(method, target, extra)
        }
        if (response.status == 401) {
            throw CameraException(CameraException.Kind.AUTH, "The camera at $where rejected the username or password.")
        }
        if (response.status == 403) {
            throw CameraException(CameraException.Kind.AUTH, "The camera at $where refused access for that user (RTSP 403).")
        }
        return response
    }

    private fun exchange(method: String, target: String, extra: List<Pair<String, String>>): Response {
        val sent = send(method, target, extra)
        while (true) {
            val response = readResponse()
            val replyCseq = response.header("CSeq").trim().toIntOrNull()
            if (replyCseq == null || replyCseq >= sent) return response
        }
    }

    private fun send(method: String, target: String, extra: List<Pair<String, String>>): Int {
        val number = cseq++
        val text = StringBuilder()
        text.append("$method $target RTSP/1.0\r\n")
        text.append("CSeq: $number\r\n")
        text.append("User-Agent: TillRecorder\r\n")
        authorization(method, target)?.let { text.append("Authorization: $it\r\n") }
        for ((name, value) in extra) if (value.isNotEmpty()) text.append("$name: $value\r\n")
        text.append("\r\n")
        try {
            output.write(text.toString().toByteArray(StandardCharsets.UTF_8))
            output.flush()
        } catch (error: java.io.IOException) {
            throw CameraException(CameraException.Kind.UNREACHABLE, "The camera at $where closed the connection.")
        }
        return number
    }

    private fun authorization(method: String, target: String): String? {
        val c = challenge ?: return null
        if (scheme == "basic") {
            return "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(StandardCharsets.UTF_8))
        }
        nonceCount += 1
        return digestHeader(c, user, password, method, target, nonceCount, randomCnonce())
    }

    private fun readResponse(): Response {
        try {
            return readResponseRest("")
        } catch (error: SocketTimeoutException) {
            throw CameraException(CameraException.Kind.PROTOCOL, "The camera at $where stopped answering RTSP requests (timed out). Is $where really an RTSP port?")
        }
    }

    private fun readResponseRest(prefix: String): Response {
        val lines = ArrayList<String>()
        val line = StringBuilder(prefix)
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (lines.isEmpty() && line.isEmpty()) {
                    throw CameraException(CameraException.Kind.PROTOCOL, "The camera at $where closed the RTSP connection.")
                }
                break
            }
            if (b == '\n'.code) {
                val done = line.toString().trimEnd('\r')
                line.setLength(0)
                if (done.isEmpty()) {
                    if (lines.isEmpty()) continue
                    break
                }
                lines.add(done)
                if (lines.size > 200) break
                continue
            }
            line.append(b.toChar())
            if (line.length > 16_384) break
        }
        val status = lines.firstOrNull().orEmpty()
        if (!status.startsWith("RTSP/")) {
            throw CameraException(CameraException.Kind.PROTOCOL, "The service at $where is not an RTSP camera stream.")
        }
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        val reason = status.split(' ', limit = 3).getOrNull(2).orEmpty()
        val headers = lines.drop(1).mapNotNull {
            val colon = it.indexOf(':')
            if (colon <= 0) null else it.substring(0, colon).trim() to it.substring(colon + 1).trim()
        }
        val length = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.toIntOrNull() ?: 0
        val body = ByteArray(length.coerceIn(0, 1_000_000))
        var filled = 0
        while (filled < body.size) {
            val n = input.read(body, filled, body.size - filled)
            if (n <= 0) break
            filled += n
        }
        return Response(code, reason, headers, String(body, 0, filled, StandardCharsets.UTF_8))
    }

    private fun parseSdp(sdp: String, base: String): Track {
        val result = parseSdpText(sdp, base, url)
        if (result.codec == "H264") return result
        val name = when (result.codec) {
            "H265", "HEVC" -> "H.265"
            "JPEG" -> "MJPEG"
            else -> result.codec
        }
        throw CameraException(
            CameraException.Kind.CODEC,
            "The camera at $where sends $name video, which the watch page cannot play. In the camera's video settings set this stream (or the sub stream) to H.264, or use the sub stream address.",
        )
    }

    companion object {
        private val random = SecureRandom()

        private fun randomCnonce(): String {
            val bytes = ByteArray(8).also { random.nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** Picks Digest over Basic from one or more WWW-Authenticate headers. Keys are lower case; "#scheme" holds the scheme. */
        fun pickChallenge(values: List<String>): Map<String, String>? {
            val parsed = values.mapNotNull { parseChallenge(it) }
            return parsed.firstOrNull { it["#scheme"] == "digest" } ?: parsed.firstOrNull { it["#scheme"] == "basic" }
        }

        fun parseChallenge(value: String): Map<String, String>? {
            val text = value.trim()
            val space = text.indexOf(' ')
            val scheme = (if (space < 0) text else text.substring(0, space)).lowercase()
            if (scheme != "digest" && scheme != "basic") return null
            val map = HashMap<String, String>()
            map["#scheme"] = scheme
            if (space < 0) return map
            val params = text.substring(space + 1)
            var i = 0
            while (i < params.length) {
                while (i < params.length && (params[i] == ',' || params[i] == ' ')) i++
                val eq = params.indexOf('=', i)
                if (eq < 0) break
                val key = params.substring(i, eq).trim().lowercase()
                i = eq + 1
                val valueText: String
                if (i < params.length && params[i] == '"') {
                    val sb = StringBuilder()
                    i++
                    while (i < params.length && params[i] != '"') {
                        if (params[i] == '\\' && i + 1 < params.length) i++
                        sb.append(params[i])
                        i++
                    }
                    i++
                    valueText = sb.toString()
                } else {
                    val end = params.indexOf(',', i).let { if (it < 0) params.length else it }
                    valueText = params.substring(i, end).trim()
                    i = end
                }
                map[key] = valueText
            }
            return map
        }

        fun digestHeader(
            c: Map<String, String>,
            user: String,
            password: String,
            method: String,
            uri: String,
            nc: Int,
            cnonce: String,
        ): String {
            val realm = c["realm"].orEmpty()
            val nonce = c["nonce"].orEmpty()
            val algorithm = c["algorithm"].orEmpty()
            val qop = c["qop"].orEmpty().split(',').map { it.trim() }.firstOrNull { it.equals("auth", true) }
            var ha1 = md5("$user:$realm:$password")
            if (algorithm.equals("MD5-sess", true)) ha1 = md5("$ha1:$nonce:$cnonce")
            val ha2 = md5("$method:$uri")
            val ncText = "%08x".format(nc)
            val response = if (qop != null) md5("$ha1:$nonce:$ncText:$cnonce:auth:$ha2") else md5("$ha1:$nonce:$ha2")
            val sb = StringBuilder("Digest ")
            sb.append("username=\"${quote(user)}\", realm=\"${quote(realm)}\", nonce=\"${quote(nonce)}\", uri=\"${quote(uri)}\", response=\"$response\"")
            if (algorithm.isNotEmpty()) sb.append(", algorithm=$algorithm")
            c["opaque"]?.let { sb.append(", opaque=\"${quote(it)}\"") }
            if (qop != null) sb.append(", qop=auth, nc=$ncText, cnonce=\"$cnonce\"")
            return sb.toString()
        }

        private fun quote(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

        /** Finds the first video track in an SDP answer and its control/play URLs and parameter sets. */
        fun parseSdpText(sdp: String, base: String, requestUrl: String): Track {
            var inVideo = false
            var seenVideo = false
            var sessionControl = ""
            var payloadType = -1
            var codec = ""
            var control = ""
            var sprop = ""
            for (raw in sdp.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("m=")) {
                    if (seenVideo) break
                    inVideo = line.startsWith("m=video")
                    if (inVideo) {
                        seenVideo = true
                        payloadType = line.split(' ').getOrNull(3)?.toIntOrNull() ?: -1
                    }
                    continue
                }
                if (!seenVideo && line.startsWith("a=control:")) sessionControl = line.substringAfter("a=control:").trim()
                if (!inVideo) continue
                when {
                    line.startsWith("a=control:") -> control = line.substringAfter("a=control:").trim()
                    line.startsWith("a=rtpmap:") -> {
                        val pt = line.substringAfter("a=rtpmap:").substringBefore(' ').toIntOrNull()
                        if (pt == payloadType || codec.isEmpty()) {
                            codec = line.substringAfter(' ').substringBefore('/').trim().uppercase()
                        }
                    }
                    line.startsWith("a=fmtp:") -> {
                        Regex("sprop-parameter-sets=([^;\\s]+)").find(line)?.groupValues?.get(1)?.let { sprop = it }
                    }
                }
            }
            if (!seenVideo) {
                throw CameraException(CameraException.Kind.PROTOCOL, "The camera stream has no video track.")
            }
            if (codec.isEmpty()) codec = if (payloadType == 26) "JPEG" else "H264"
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            for (part in sprop.split(',')) {
                val bytes = try {
                    Base64.getDecoder().decode(part.trim())
                } catch (_: Exception) {
                    null
                } ?: continue
                if (bytes.isEmpty()) continue
                when (bytes[0].toInt() and 0x1f) {
                    7 -> sps = bytes
                    8 -> pps = bytes
                }
            }
            val playUrl = when {
                sessionControl.startsWith("rtsp://", true) -> sessionControl
                else -> base
            }
            return Track(codec, payloadType, joinControl(base, control), playUrl, sps, pps)
        }

        fun joinControl(base: String, control: String): String {
            if (control.isEmpty() || control == "*") return base
            if (control.startsWith("rtsp://", true) || control.startsWith("rtsps://", true)) return control
            return base.trimEnd('/') + "/" + control.trimStart('/')
        }

        /**
         * Checks the address answers DESCRIBE with a playable H.264 video track.
         * Returns null when it is fine, otherwise a message saying exactly what failed.
         */
        fun probe(bareUrl: String, user: String, password: String): CameraException? {
            return try {
                RtspClient(bareUrl, user, password).use { client ->
                    client.connect(6_000, 8_000)
                    client.describe()
                }
                null
            } catch (error: CameraException) {
                error
            } catch (error: Exception) {
                CameraException(CameraException.Kind.PROTOCOL, "The camera stream could not be opened (${error.javaClass.simpleName}).")
            }
        }
    }
}

/**
 * RTP H.264 depacketizer (RFC 6184 single NAL, STAP-A, FU-A) that groups NAL units
 * into access units by RTP timestamp / marker bit.
 */
class RtpH264Assembler {
    class AccessUnit(val nals: List<ByteArray>, val keyframe: Boolean, val timestamp: Long)

    var sps: ByteArray? = null
    var pps: ByteArray? = null
    private val pending = ArrayList<ByteArray>()
    private var pendingTs = -1L
    private var fragment: ByteArrayOutputStream? = null

    /** Feeds one RTP packet; returns any completed access units. */
    fun push(rtp: ByteArray): List<AccessUnit> {
        if (rtp.size < 12 || (rtp[0].toInt() and 0xc0) != 0x80) return emptyList()
        val padding = rtp[0].toInt() and 0x20 != 0
        val extension = rtp[0].toInt() and 0x10 != 0
        val csrc = rtp[0].toInt() and 0x0f
        val marker = rtp[1].toInt() and 0x80 != 0
        val ts = ((rtp[4].toLong() and 0xff) shl 24) or ((rtp[5].toLong() and 0xff) shl 16) or
            ((rtp[6].toLong() and 0xff) shl 8) or (rtp[7].toLong() and 0xff)
        var offset = 12 + csrc * 4
        if (extension) {
            if (rtp.size < offset + 4) return emptyList()
            val words = ((rtp[offset + 2].toInt() and 0xff) shl 8) or (rtp[offset + 3].toInt() and 0xff)
            offset += 4 + words * 4
        }
        var end = rtp.size
        if (padding && end > offset) end -= rtp[end - 1].toInt() and 0xff
        if (end <= offset) return emptyList()
        val out = ArrayList<AccessUnit>()
        if (pendingTs >= 0 && ts != pendingTs && pending.isNotEmpty()) flush()?.let { out.add(it) }
        pendingTs = ts
        val type = rtp[offset].toInt() and 0x1f
        when (type) {
            in 1..23 -> add(rtp.copyOfRange(offset, end))
            24 -> {
                var i = offset + 1
                while (i + 2 <= end) {
                    val size = ((rtp[i].toInt() and 0xff) shl 8) or (rtp[i + 1].toInt() and 0xff)
                    i += 2
                    if (size <= 0 || i + size > end) break
                    add(rtp.copyOfRange(i, i + size))
                    i += size
                }
            }
            28 -> {
                if (end - offset < 2) return out
                val fu = rtp[offset + 1].toInt()
                val start = fu and 0x80 != 0
                val stop = fu and 0x40 != 0
                if (start) {
                    fragment = ByteArrayOutputStream().also {
                        it.write((rtp[offset].toInt() and 0xe0) or (fu and 0x1f))
                    }
                }
                val current = fragment
                if (current != null) {
                    current.write(rtp, offset + 2, end - offset - 2)
                    if (stop) {
                        add(current.toByteArray())
                        fragment = null
                    }
                }
            }
        }
        if (marker) flush()?.let { out.add(it) }
        return out
    }

    private fun add(nal: ByteArray) {
        if (nal.isEmpty()) return
        when (nal[0].toInt() and 0x1f) {
            7 -> sps = nal
            8 -> pps = nal
        }
        pending.add(nal)
    }

    private fun flush(): AccessUnit? {
        if (pending.isEmpty()) return null
        val nals = pending.filter {
            val t = it[0].toInt() and 0x1f
            t != 7 && t != 8 && t != 9 && t != 6
        }
        val key = pending.any { (it[0].toInt() and 0x1f) == 5 }
        pending.clear()
        if (nals.isEmpty()) return null
        return AccessUnit(nals, key, pendingTs)
    }

    companion object {
        /** Joins NAL units as 4-byte length-prefixed (AVCC) sample data. */
        fun avcc(nals: List<ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            for (nal in nals) {
                val n = nal.size
                out.write(n ushr 24); out.write(n ushr 16); out.write(n ushr 8); out.write(n)
                out.write(nal)
            }
            return out.toByteArray()
        }
    }
}
