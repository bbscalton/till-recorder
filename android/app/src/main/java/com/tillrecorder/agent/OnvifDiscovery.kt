package com.tillrecorder.agent

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * WS-Discovery on the store LAN. The watch page never sends this probe.
 */
object OnvifDiscovery {
    data class Camera(val name: String, val host: String, val service: String)

    fun probe(context: Context): List<Camera> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("till-onvif")
        lock?.setReferenceCounted(false)
        lock?.acquire()
        val found = LinkedHashMap<String, Camera>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 700
                val probe = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
                    <e:Header><w:MessageID>urn:uuid:11111111-1111-1111-1111-111111111111</w:MessageID><w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>
                    <e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>
                """.trimIndent().toByteArray(Charsets.UTF_8)
                val group = InetAddress.getByName("239.255.255.250")
                socket.send(DatagramPacket(probe, probe.size, group, 3702))
                val until = System.currentTimeMillis() + 2_000
                val buffer = ByteArray(8192)
                while (System.currentTimeMillis() < until) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                        val xaddr = Regex("XAddrs>([^<\\s]+)").find(text)?.groupValues?.getOrNull(1)
                        val raw = xaddr ?: packet.address?.hostAddress ?: continue
                        val ip = Regex("^(?:https?://)?([^/:]+)").find(raw)?.groupValues?.getOrNull(1) ?: raw
                        if (ip.isBlank() || found.containsKey(ip)) continue
                        val service = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "rtsp://$ip:554/"
                        found[ip] = Camera("Camera", ip, service)
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                lock?.release()
            } catch (_: Exception) {
            }
        }
        return found.values.toList()
    }

    fun tryStream(xaddr: String, user: String, password: String): String? {
        try {
            if (!xaddr.startsWith("http://") && !xaddr.startsWith("https://")) return null
            var media = xaddr
            var profiles = post(media, envelope(user, password, """<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>"""))
            var token = Regex("""token="([^"]+)"""").find(profiles)?.groupValues?.getOrNull(1).orEmpty()
            if (token.isEmpty()) {
                val caps = post(xaddr, envelope(user, password, """<GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl"><Category>Media</Category></GetCapabilities>"""))
                media = Regex("XAddr>([^<\\s]+)").find(caps)?.groupValues?.getOrNull(1) ?: return null
                profiles = post(media, envelope(user, password, """<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>"""))
                token = Regex("""token="([^"]+)"""").find(profiles)?.groupValues?.getOrNull(1).orEmpty()
            }
            if (token.isEmpty()) return null
            val safeToken = xml(token)
            val body = """<GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl"><StreamSetup><Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream><Transport xmlns="http://www.onvif.org/ver10/schema"><Protocol>RTSP</Protocol></Transport></StreamSetup><ProfileToken>$safeToken</ProfileToken></GetStreamUri>"""
            val stream = post(media, envelope(user, password, body))
            val uri = Regex("<(?:[\\w]+:)?Uri>([^<]+)</").find(stream)?.groupValues?.getOrNull(1)?.trim() ?: return null
            return CameraAddress.bare(uri)
        } catch (error: Exception) {
            Log.w("TillRecorder", "onvif ${error.javaClass.simpleName}")
            return null
        }
    }

    private fun envelope(user: String, password: String, body: String): String {
        return """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"><s:Header>${security(user, password)}</s:Header><s:Body>$body</s:Body></s:Envelope>"""
    }

    private fun security(user: String, password: String): String {
        if (user.isEmpty()) return ""
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val mix = nonce + created.toByteArray(Charsets.UTF_8) + password.toByteArray(Charsets.UTF_8)
        val digest = Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest(mix), Base64.NO_WRAP)
        val nonceText = Base64.encodeToString(nonce, Base64.NO_WRAP)
        return """<Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><UsernameToken><Username>${xml(user)}</Username><Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</Password><Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceText</Nonce><Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created></UsernameToken></Security>"""
    }

    private fun xml(value: String): String {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    private fun post(address: String, xml: String): String {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
        conn.outputStream.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return text
    }
}
