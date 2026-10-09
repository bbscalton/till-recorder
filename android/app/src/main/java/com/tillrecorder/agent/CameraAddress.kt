package com.tillrecorder.agent

/**
 * Keeps the camera address and its username and password separate until the
 * register opens the stream. The watch page never receives the password.
 *
 * Pure Kotlin (no android.net.Uri) so it runs in local unit tests and so a
 * password with @ / ? # : % characters is never URL-parsed.
 */
object CameraAddress {
    data class Parts(val bare: String, val user: String, val password: String)

    enum class Kind { RTSP, ONVIF, INVALID }

    data class Target(
        val kind: Kind,
        val url: String,
        val user: String,
        val password: String,
        val error: String = "",
    )

    /** scheme is lower case, port is -1 when absent, path keeps the query ("/a/b?x=1"). */
    data class Url(val scheme: String, val host: String, val port: Int, val path: String) {
        fun hostPort(): String = if (port > 0) "${hostText()}:$port" else hostText()
        fun hostText(): String = if (host.contains(':')) "[$host]" else host
        override fun toString(): String = "$scheme://${hostPort()}$path"
    }

    private val RTSP_PORTS = setOf(554, 8554, 10554)

    fun split(raw: String): Parts {
        val text = raw.trim()
        val schemeEnd = text.indexOf("://")
        val prefix = if (schemeEnd >= 0) text.substring(0, schemeEnd + 3) else ""
        val rest = if (schemeEnd >= 0) text.substring(schemeEnd + 3) else text
        val at = rest.lastIndexOf('@')
        if (at < 0) return Parts(text, "", "")
        val info = rest.substring(0, at)
        val colon = info.indexOf(':')
        val user = decode(if (colon >= 0) info.substring(0, colon) else info)
        val password = if (colon >= 0) decode(info.substring(colon + 1)) else ""
        return Parts(prefix + rest.substring(at + 1), user, password)
    }

    fun bare(raw: String): String = split(raw).bare

    fun embed(bare: String, user: String, password: String): String {
        val text = bare.trim()
        if (text.isEmpty() || user.isEmpty()) return text
        val schemeEnd = text.indexOf("://")
        if (schemeEnd < 0) return text
        val rest = split(text).bare.substring(schemeEnd + 3)
        return text.substring(0, schemeEnd + 3) + encode(user) + ":" + encode(password) + "@" + rest
    }

    fun label(bare: String): String = parse(split(bare).bare)?.host?.take(60).orEmpty()

    /** Parses scheme://host[:port][/path][?query] (no user info). Returns null when it is not a usable address. */
    fun parse(text: String): Url? {
        val trimmed = text.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        var rest = trimmed.substring(schemeEnd + 3)
        val hash = rest.indexOf('#')
        if (hash >= 0) rest = rest.substring(0, hash)
        val pathStart = rest.indexOfFirst { it == '/' || it == '?' }
        val authority = if (pathStart >= 0) rest.substring(0, pathStart) else rest
        var path = if (pathStart >= 0) rest.substring(pathStart) else ""
        if (path.startsWith("?")) path = "/$path"
        if (authority.contains('@')) return null
        val host: String
        var portText = ""
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(1, close)
            val after = authority.substring(close + 1)
            if (after.isNotEmpty()) {
                if (!after.startsWith(":")) return null
                portText = after.substring(1)
            }
            if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }) return null
        } else {
            val colon = authority.lastIndexOf(':')
            host = if (colon >= 0) authority.substring(0, colon) else authority
            if (colon >= 0) portText = authority.substring(colon + 1)
            if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
        }
        var port = -1
        if (portText.isNotEmpty()) {
            port = portText.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
        }
        return Url(scheme, host, port, path)
    }

    /**
     * Turns what the user typed into a camera target.
     *  - 192.168.1.20 or cam.local or 192.168.1.20:8000 -> ONVIF http://host[:port]/onvif/device_service
     *  - 192.168.1.20:554/path -> rtsp://...
     *  - http(s)://host[:port][/onvif/device_service] -> ONVIF (path added when missing)
     *  - rtsp://[user:pass@]host[:port]/path?query -> RTSP
     * Username/password fields win; otherwise credentials in the address are used.
     */
    fun resolve(raw: String, fieldUser: String, fieldPassword: String): Target {
        val parts = split(raw.trim())
        val user = fieldUser.trim().ifEmpty { parts.user }
        val password = fieldPassword.ifEmpty { parts.password }
        var text = parts.bare.trim().replace("&amp;", "&")
        if (text.isEmpty()) return invalid("Enter the camera address.", user, password)
        val hasScheme = text.contains("://")
        if (!hasScheme) {
            val guess = parse("x://$text") ?: return invalid(BAD_ADDRESS, user, password)
            text = if (guess.port in RTSP_PORTS) {
                "rtsp://" + guess.hostPort() + guess.path.ifEmpty { "/" }
            } else {
                "http://" + guess.hostPort() + guess.path.ifEmpty { ONVIF_PATH }
            }
        }
        val url = parse(text) ?: return invalid(BAD_ADDRESS, user, password)
        return when (url.scheme) {
            "rtsp" -> Target(Kind.RTSP, url.copy(path = url.path.ifEmpty { "/" }).toString(), user, password)
            "rtsps" -> invalid("rtsps:// (encrypted RTSP) is not supported. Use the camera's rtsp:// address.", user, password)
            "http", "https" -> {
                val path = if (url.path.isEmpty() || url.path == "/") ONVIF_PATH else url.path
                Target(Kind.ONVIF, url.copy(path = path).toString(), user, password)
            }
            else -> invalid(BAD_ADDRESS, user, password)
        }
    }

    /** Sub stream address for the common Dahua and Hikvision path forms, or null. */
    fun subStream(url: String): String? {
        val dahua = Regex("([?&]subtype=)0(?=&|$)", RegexOption.IGNORE_CASE)
        if (dahua.containsMatchIn(url)) return dahua.replace(url) { it.groupValues[1] + "1" }
        val hik = Regex("(/Streaming/Channels/\\d*?)01(?=[/?]|$)", RegexOption.IGNORE_CASE)
        if (hik.containsMatchIn(url)) return hik.replace(url) { it.groupValues[1] + "02" }
        return null
    }

    private fun invalid(message: String, user: String, password: String) = Target(Kind.INVALID, "", user, password, message)

    fun encode(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                out.append(ch)
            } else {
                out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }
        return out.toString()
    }

    /** Decodes %XX sequences only; a lone % or + stays as typed. */
    fun decode(value: String): String {
        if (!value.contains('%')) return value
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length && hex(value[i + 1]) >= 0 && hex(value[i + 2]) >= 0) {
                bytes.write(hex(value[i + 1]) * 16 + hex(value[i + 2]))
                i += 3
            } else {
                bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
                i += 1
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun hex(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }

    const val ONVIF_PATH = "/onvif/device_service"
    const val BAD_ADDRESS = "That address was not understood. Use the camera's IP address, an ONVIF address (http://IP/onvif/device_service) or an RTSP address (rtsp://IP:554/path)."
}
