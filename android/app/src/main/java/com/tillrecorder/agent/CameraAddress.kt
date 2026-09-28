package com.tillrecorder.agent

import android.net.Uri

/**
 * Keeps the camera address and its username and password separate until the
 * register opens the stream. The watch page never receives the password.
 */
object CameraAddress {
    data class Parts(val bare: String, val user: String, val password: String)

    fun split(raw: String): Parts {
        val text = raw.trim()
        val scheme = text.indexOf("://")
        if (scheme < 0) return Parts(text, "", "")
        val after = text.substring(scheme + 3)
        val at = after.lastIndexOf('@')
        if (at < 0) return Parts(text, "", "")
        val info = after.substring(0, at)
        val colon = info.indexOf(':')
        val user = Uri.decode(if (colon >= 0) info.substring(0, colon) else info)
        val password = if (colon >= 0) Uri.decode(info.substring(colon + 1)) else ""
        return Parts(bare(text), user, password)
    }

    fun bare(raw: String): String {
        val text = raw.trim()
        val uri = Uri.parse(text)
        val scheme = uri.scheme ?: return text
        val host = uri.host ?: return text
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.encodedPath ?: ""
        val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
        return "$scheme://$host$port$path$query"
    }

    fun embed(bare: String, user: String, password: String): String {
        val text = bare.trim()
        if (text.isEmpty() || user.isEmpty()) return text
        val uri = Uri.parse(text)
        val scheme = uri.scheme ?: return text
        val host = uri.host ?: return text
        val encodedUser = Uri.encode(user)
        val encodedPassword = Uri.encode(password)
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.encodedPath ?: ""
        val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
        return "$scheme://$encodedUser:$encodedPassword@$host$port$path$query"
    }

    fun label(bare: String): String {
        val host = Uri.parse(bare.trim()).host ?: return ""
        return host.take(60)
    }
}
