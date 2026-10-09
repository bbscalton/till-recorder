package com.tillrecorder.agent

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * WS-Discovery on the store LAN and ONVIF stream lookup. The watch page never sends this probe.
 */
object OnvifDiscovery {
    data class Camera(val name: String, val host: String, val service: String)

    data class Profile(val token: String, val encoding: String, val width: Int, val height: Int)

    /** Either a list of RTSP stream addresses (best first, no credentials) or an error in plain words. */
    data class Streams(val urls: List<String>, val error: CameraException?)

    fun probeMessage(): ByteArray = """
        <?xml version="1.0" encoding="UTF-8"?>
        <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
        <e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>
        <e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>
    """.trimIndent().toByteArray(Charsets.UTF_8)

    /** When the probe is (re)sent, in ms from the start, and how long replies are collected. */
    private val PROBE_ROUNDS = longArrayOf(0, 800, 1_800)
    private const val LISTEN_MS = 4_000L

    /**
     * WS-Discovery Probe to 239.255.255.250:3702 from every active IPv4 interface (three rounds, same MessageID),
     * collecting every ProbeMatch for four seconds. Multicast stays on the local subnet: cameras behind a router
     * on another subnet do not hear it and are added by typing their IP address.
     */
    fun probe(context: Context): List<Camera> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("till-onvif")
        lock?.setReferenceCounted(false)
        lock?.acquire()
        val found = Found()
        val sockets = mutableListOf<DatagramSocket>()
        try {
            for ((nic, address) in multicastAddresses()) {
                try {
                    val socket = MulticastSocket(InetSocketAddress(address, 0))
                    socket.networkInterface = nic
                    sockets.add(socket)
                } catch (_: Exception) {
                }
            }
            if (sockets.isEmpty()) sockets.add(DatagramSocket())
            sockets.forEach { it.soTimeout = 40 }
            val probe = probeMessage()
            val group = InetAddress.getByName("239.255.255.250")
            val started = System.currentTimeMillis()
            val buffer = ByteArray(16384)
            var round = 0
            while (System.currentTimeMillis() - started < LISTEN_MS) {
                if (round < PROBE_ROUNDS.size && System.currentTimeMillis() - started >= PROBE_ROUNDS[round]) {
                    for (socket in sockets) {
                        try { socket.send(DatagramPacket(probe, probe.size, group, 3702)) } catch (_: Exception) {}
                    }
                    round++
                }
                for (socket in sockets) {
                    while (true) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            found.add(String(packet.data, packet.offset, packet.length, Charsets.UTF_8), packet.address?.hostAddress.orEmpty())
                        } catch (_: SocketTimeoutException) {
                            break
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            sockets.forEach { try { it.close() } catch (_: Exception) {} }
            try {
                lock?.release()
            } catch (_: Exception) {
            }
        }
        return found.cameras
    }

    private fun multicastAddresses(): List<Pair<NetworkInterface, InetAddress>> {
        val result = mutableListOf<Pair<NetworkInterface, InetAddress>>()
        try {
            for (nic in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                if (!nic.isUp || nic.isLoopback || !nic.supportsMulticast()) continue
                for (address in nic.inetAddresses.toList()) {
                    if (address !is Inet4Address || address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    result.add(nic to address)
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    /** Every camera that answered, once each, keyed by the device's own address (its XAddr host), sorted by IP. */
    class Found {
        private val byHost = LinkedHashMap<String, Camera>()
        val cameras: List<Camera> get() = byHost.values.sortedWith(compareBy({ hostKey(it.host) }, { it.host }))

        fun add(reply: String, sender: String): Boolean {
            val camera = cameraFromReply(reply, sender) ?: return false
            if (byHost.containsKey(camera.host)) return false
            byHost[camera.host] = camera
            return true
        }

        private fun hostKey(host: String): Long {
            val parts = host.split('.')
            if (parts.size != 4) return Long.MAX_VALUE
            return parts.fold(0L) { acc, part -> acc * 256 + (part.toIntOrNull() ?: return Long.MAX_VALUE) }
        }
    }

    /** Model (or name) from the ONVIF scopes in a ProbeMatch, e.g. onvif://www.onvif.org/hardware/IPC-HDW1230S. */
    fun modelFromReply(text: String): String {
        val scopes = Regex("Scopes[^>]*>([^<]+)<").find(text)?.groupValues?.getOrNull(1).orEmpty()
            .split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun scope(kind: String): String {
            val prefix = "onvif://www.onvif.org/$kind/"
            val hit = scopes.firstOrNull { it.startsWith(prefix, true) } ?: return ""
            return try {
                java.net.URLDecoder.decode(hit.substring(prefix.length).replace("+", "%2B"), "UTF-8")
            } catch (_: Exception) {
                hit.substring(prefix.length)
            }.replace('_', ' ').trim()
        }
        val hardware = scope("hardware")
        val name = scope("name")
        if (hardware.isNotEmpty() && name.isNotEmpty() && !name.equals(hardware, true)) return "$name $hardware"
        return hardware.ifEmpty { name }
    }

    /** Picks the IPv4 http XAddr from a ProbeMatch (XAddrs is a space separated list that may start with IPv6). */
    fun cameraFromReply(text: String, sender: String): Camera? {
        if (!text.contains("ProbeMatch")) return null
        val xaddrs = Regex("XAddrs>([^<]+)<").find(text)?.groupValues?.getOrNull(1).orEmpty()
            .split(Regex("\\s+")).filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
        val ipv4 = xaddrs.firstOrNull { CameraAddress.parse(it)?.host?.let(::isIpv4) == true }
        val chosen = ipv4 ?: xaddrs.firstOrNull { CameraAddress.parse(it)?.host == sender }
        val service = chosen ?: if (isIpv4(sender)) "http://$sender${CameraAddress.ONVIF_PATH}" else return null
        val host = CameraAddress.parse(service)?.host ?: return null
        return Camera(modelFromReply(text).ifEmpty { "Camera" }, host, service)
    }

    private fun isIpv4(host: String) = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)

    /**
     * Asks the ONVIF device for its RTSP stream addresses. H.264 profiles come first,
     * then the sub stream, then the rest. Credentials are kept out of the returned addresses.
     */
    fun streams(device: String, user: String, password: String): Streams {
        val where = CameraAddress.parse(device)?.hostPort() ?: device
        try {
            val offset = clockOffset(device)
            val session = Soap(user, password, offset)
            val caps = session.call(device, """<GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl"><Category>Media</Category></GetCapabilities>""")
            var media = if (caps.ok) mediaXAddr(caps.text) ?: device else device
            if (caps.status == 401 || isNotAuthorized(caps.text)) return Streams(emptyList(), authError(where, user))
            if (caps.status == 404) {
                return Streams(emptyList(), CameraException(CameraException.Kind.NOT_FOUND, "No ONVIF service at $where (HTTP 404). Check the ONVIF port (often 80, 8000, 8080 or 8899) and that ONVIF is turned on in the camera."))
            }
            media = sameHost(media, device)
            var profilesReply = session.call(media, """<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>""")
            if (!profilesReply.ok && media != device) {
                media = device
                profilesReply = session.call(media, """<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>""")
            }
            if (profilesReply.status == 401 || isNotAuthorized(profilesReply.text)) return Streams(emptyList(), authError(where, user))
            val ordered = order(profiles(profilesReply.text))
            if (ordered.isEmpty()) {
                return Streams(emptyList(), CameraException(CameraException.Kind.PROTOCOL, "The camera at $where answered ONVIF but listed no video profiles (HTTP ${profilesReply.status})."))
            }
            val urls = ArrayList<String>()
            for (profile in ordered) {
                val body = """<GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl"><StreamSetup><Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream><Transport xmlns="http://www.onvif.org/ver10/schema"><Protocol>RTSP</Protocol></Transport></StreamSetup><ProfileToken>${xml(profile.token)}</ProfileToken></GetStreamUri>"""
                val reply = session.call(media, body)
                if (reply.status == 401 || isNotAuthorized(reply.text)) return Streams(emptyList(), authError(where, user))
                val uri = streamUri(reply.text) ?: continue
                val bare = sameHost(CameraAddress.bare(uri), device)
                if (bare !in urls) urls.add(bare)
            }
            if (urls.isEmpty()) {
                return Streams(emptyList(), CameraException(CameraException.Kind.PROTOCOL, "The camera at $where did not give an RTSP stream address over ONVIF."))
            }
            return Streams(urls, null)
        } catch (error: CameraException) {
            return Streams(emptyList(), error)
        } catch (error: Exception) {
            Log.w("TillRecorder", "onvif ${error.javaClass.simpleName}")
            return Streams(emptyList(), CameraException(CameraException.Kind.PROTOCOL, "ONVIF lookup at $where failed (${error.javaClass.simpleName})."))
        }
    }

    private fun authError(where: String, user: String) = CameraException(
        CameraException.Kind.AUTH,
        if (user.isEmpty()) "The camera at $where needs a username and password for ONVIF."
        else "The camera at $where rejected the username or password (ONVIF). Check them, and that the camera clock is right.",
    )

    /** Cameras behind a different interface sometimes report another IP; keep the host the user reached. */
    fun sameHost(address: String, device: String): String {
        val reported = CameraAddress.parse(address) ?: return address
        val reached = CameraAddress.parse(device) ?: return address
        val bogus = reported.host == "0.0.0.0" || reported.host == "127.0.0.1" || reported.host.equals("localhost", true)
        if (!bogus) return address
        return reported.copy(host = reached.host).toString()
    }

    fun profiles(xmlText: String): List<Profile> {
        val result = ArrayList<Profile>()
        val starts = Regex("<(?:[\\w-]+:)?Profiles\\b([^>]*)>").findAll(xmlText).toList()
        for ((index, match) in starts.withIndex()) {
            val token = Regex("token=\"([^\"]+)\"").find(match.groupValues[1])?.groupValues?.get(1) ?: continue
            val end = if (index + 1 < starts.size) starts[index + 1].range.first else xmlText.length
            val chunk = xmlText.substring(match.range.last + 1, end)
            val encoder = Regex("<(?:[\\w-]+:)?VideoEncoderConfiguration\\b.*?</(?:[\\w-]+:)?VideoEncoderConfiguration>", RegexOption.DOT_MATCHES_ALL)
                .find(chunk)?.value.orEmpty()
            val encoding = Regex("<(?:[\\w-]+:)?Encoding>\\s*([^<\\s]+)\\s*<").find(encoder)?.groupValues?.get(1).orEmpty().uppercase()
            val width = Regex("<(?:[\\w-]+:)?Width>(\\d+)<").find(encoder)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val height = Regex("<(?:[\\w-]+:)?Height>(\\d+)<").find(encoder)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            result.add(Profile(unescape(token), encoding, width, height))
        }
        return result
    }

    /** H.264 first (main stream before sub stream), then the sub stream, then the rest. */
    fun order(profiles: List<Profile>): List<Profile> {
        val h264 = profiles.filter { it.encoding == "H264" }
        val unknown = profiles.filter { it.encoding.isEmpty() }
        val rest = profiles.filter { it.encoding.isNotEmpty() && it.encoding != "H264" }
        val sub = rest.drop(1).take(1)
        return (h264 + unknown + sub + rest).distinct()
    }

    fun streamUri(xmlText: String): String? {
        val raw = Regex("<(?:[\\w-]+:)?Uri>\\s*([^<]+?)\\s*</").find(xmlText)?.groupValues?.getOrNull(1) ?: return null
        val uri = unescape(raw)
        return if (uri.startsWith("rtsp://", true)) uri else null
    }

    fun mediaXAddr(xmlText: String): String? {
        val media = Regex("<(?:[\\w-]+:)?Media>.*?<(?:[\\w-]+:)?XAddr>\\s*([^<\\s]+)\\s*<", RegexOption.DOT_MATCHES_ALL)
            .find(xmlText)?.groupValues?.getOrNull(1) ?: return null
        return unescape(media)
    }

    fun isNotAuthorized(xmlText: String): Boolean =
        xmlText.contains("NotAuthorized", true) || xmlText.contains("FailedAuthentication", true) ||
            xmlText.contains("Sender not Authorized", true)

    /** Camera UTC time from GetSystemDateAndTime, or null. */
    fun cameraUtc(xmlText: String): Long? {
        val utc = Regex("<(?:[\\w-]+:)?UTCDateTime>(.*?)</(?:[\\w-]+:)?UTCDateTime>", RegexOption.DOT_MATCHES_ALL)
            .find(xmlText)?.groupValues?.get(1) ?: return null
        fun field(name: String) = Regex("<(?:[\\w-]+:)?$name>(\\d+)<").find(utc)?.groupValues?.get(1)?.toIntOrNull()
        val year = field("Year") ?: return null
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(year, (field("Month") ?: 1) - 1, field("Day") ?: 1, field("Hour") ?: 0, field("Minute") ?: 0, field("Second") ?: 0)
        return calendar.timeInMillis
    }

    fun unescape(value: String): String = value.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&#38;", "&").replace("&amp;", "&")

    private fun clockOffset(device: String): Long {
        val reply = Soap("", "", 0).call(device, """<GetSystemDateAndTime xmlns="http://www.onvif.org/ver10/device/wsdl"/>""")
        val camera = cameraUtc(reply.text) ?: return 0
        val offset = camera - System.currentTimeMillis()
        return if (kotlin.math.abs(offset) > 5_000) offset else 0
    }

    fun security(user: String, password: String, nowMs: Long, nonce: ByteArray): String {
        if (user.isEmpty()) return ""
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(nowMs))
        val digest = passwordDigest(nonce, created, password)
        val nonceText = Base64.getEncoder().encodeToString(nonce)
        return """<Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><UsernameToken><Username>${xml(user)}</Username><Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</Password><Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceText</Nonce><Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created></UsernameToken></Security>"""
    }

    /** WS-Security UsernameToken digest: Base64(SHA1(nonce + created + password)). */
    fun passwordDigest(nonce: ByteArray, created: String, password: String): String {
        val mix = nonce + created.toByteArray(Charsets.UTF_8) + password.toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(mix))
    }

    private fun xml(value: String): String {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    private class Reply(val status: Int, val text: String) {
        val ok get() = status in 200..299
    }

    /** One SOAP conversation: WS-Security header first, HTTP Digest/Basic when the camera asks for it. */
    private class Soap(val user: String, val password: String, val offsetMs: Long) {
        private var challenge: Map<String, String>? = null
        private var nc = 0

        fun call(address: String, body: String): Reply {
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val envelope = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"><s:Header>${security(user, password, System.currentTimeMillis() + offsetMs, nonce)}</s:Header><s:Body>$body</s:Body></s:Envelope>"""
            var reply = post(address, envelope)
            if (reply.first.status == 401 && user.isNotEmpty()) {
                val next = RtspClient.pickChallenge(reply.second)
                if (next != null) {
                    challenge = next
                    nc = 0
                    reply = post(address, envelope)
                }
            }
            return reply.first
        }

        private fun post(address: String, xmlText: String): Pair<Reply, List<String>> {
            val where = CameraAddress.parse(address)?.hostPort() ?: "the camera"
            val conn = URL(address).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.doOutput = true
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
                challenge?.let { c ->
                    val header = if (c["#scheme"] == "basic") {
                        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
                    } else {
                        nc += 1
                        val path = URL(address).let { (it.path.ifEmpty { "/" }) + (it.query?.let { q -> "?$q" } ?: "") }
                        RtspClient.digestHeader(c, user, password, "POST", path, nc, java.lang.Long.toHexString(SecureRandom().nextLong()))
                    }
                    conn.setRequestProperty("Authorization", header)
                }
                conn.outputStream.use { it.write(xmlText.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val auth = conn.headerFields.entries.filter { it.key.equals("WWW-Authenticate", true) }.flatMap { it.value }
                return Reply(code, text) to auth
            } catch (error: UnknownHostException) {
                throw CameraException(CameraException.Kind.UNREACHABLE, "The camera name $where could not be found on this network.")
            } catch (error: SocketTimeoutException) {
                throw CameraException(CameraException.Kind.UNREACHABLE, "The camera at $where did not answer ONVIF (timed out). Check the IP address and ONVIF port.")
            } catch (error: ConnectException) {
                throw CameraException(CameraException.Kind.UNREACHABLE, "The camera at $where refused the ONVIF connection. Check the ONVIF port (often 80, 8000, 8080 or 8899).")
            } finally {
                conn.disconnect()
            }
        }
    }
}
