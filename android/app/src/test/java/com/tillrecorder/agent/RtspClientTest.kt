package com.tillrecorder.agent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class RtspClientTest {
    @Test
    fun digestMatchesRfc2617Example() {
        val c = RtspClient.parseChallenge("Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"")!!
        val header = RtspClient.digestHeader(c, "Mufasa", "Circle Of Life", "GET", "/dir/index.html", 1, "0a4f113b")
        assertTrue(header, header.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(header.contains("nc=00000001"))
    }

    @Test
    fun prefersDigestOverBasic() {
        val c = RtspClient.pickChallenge(listOf("Basic realm=\"x\"", "Digest realm=\"Login to 2a1b\", nonce=\"abc\""))!!
        assertEquals("digest", c["#scheme"])
        assertEquals("Login to 2a1b", c["realm"])
    }

    @Test
    fun dahuaSdpPicksVideoTrack() {
        val sdp = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=Media Server\r\na=control:*\r\nt=0 0\r\n" +
            "m=video 0 RTP/AVP 96\r\na=control:trackID=0\r\na=rtpmap:96 H264/90000\r\n" +
            "a=fmtp:96 packetization-mode=1;profile-level-id=640028;sprop-parameter-sets=Z2QAKKwbGoB4AiflwFuAgICgAAADACAAAAMDwQ==,aO44gA==\r\n" +
            "m=audio 0 RTP/AVP 8\r\na=control:trackID=1\r\na=rtpmap:8 PCMA/8000\r\n"
        val base = "rtsp://192.168.1.111:554/cam/realmonitor?channel=1&subtype=0/"
        val track = RtspClient.parseSdpText(sdp, base, base)
        assertEquals("H264", track.codec)
        assertEquals("rtsp://192.168.1.111:554/cam/realmonitor?channel=1&subtype=0/trackID=0", track.control)
        assertEquals(base, track.playUrl)
        assertEquals(7, track.sps!![0].toInt() and 0x1f)
        assertEquals(8, track.pps!![0].toInt() and 0x1f)
    }

    @Test
    fun h265IsReportedAsCodec() {
        val sdp = "v=0\r\nm=video 0 RTP/AVP 98\r\na=rtpmap:98 H265/90000\r\na=control:trackID=0\r\n"
        assertEquals("H265", RtspClient.parseSdpText(sdp, "rtsp://h/", "rtsp://h/").codec)
    }

    @Test
    fun assemblerHandlesStapAAndFuA() {
        val a = RtpH264Assembler()
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x28)
        val pps = byteArrayOf(0x68, 0xee.toByte(), 0x38)
        val stap = rtp(1000, false, byteArrayOf(0x18, 0, sps.size.toByte()) + sps + byteArrayOf(0, pps.size.toByte()) + pps)
        assertTrue(a.push(stap).isEmpty())
        assertArrayEquals(sps, a.sps)
        assertArrayEquals(pps, a.pps)
        val fuStart = rtp(1000, false, byteArrayOf(0x7c, 0x85.toByte(), 1, 2))
        val fuEnd = rtp(1000, true, byteArrayOf(0x7c, 0x45, 3, 4))
        assertTrue(a.push(fuStart).isEmpty())
        val units = a.push(fuEnd)
        assertEquals(1, units.size)
        assertTrue(units[0].keyframe)
        assertArrayEquals(byteArrayOf(0x65, 1, 2, 3, 4), units[0].nals.single())
        assertArrayEquals(byteArrayOf(0, 0, 0, 5, 0x65, 1, 2, 3, 4), RtpH264Assembler.avcc(units[0].nals))
    }

    @Test
    fun probeDoesDigestLoginAgainstMockCamera() {
        val server = ServerSocket(0)
        val port = server.localPort
        val seen = ArrayList<String>()
        thread(isDaemon = true) {
            server.accept().use { s ->
                val reader = s.getInputStream().bufferedReader()
                val out = s.getOutputStream()
                repeat(2) {
                    val lines = ArrayList<String>()
                    while (true) {
                        val l = reader.readLine() ?: break
                        if (l.isEmpty()) break
                        lines.add(l)
                    }
                    synchronized(seen) { seen.add(lines.joinToString("\n")) }
                    val cseq = lines.first { it.startsWith("CSeq") }.substringAfter(":").trim()
                    val auth = lines.firstOrNull { it.startsWith("Authorization:") }
                    if (auth == null) {
                        out.write("RTSP/1.0 401 Unauthorized\r\nCSeq: $cseq\r\nWWW-Authenticate: Digest realm=\"Login to abc\", nonce=\"n1\", stale=\"FALSE\"\r\nWWW-Authenticate: Basic realm=\"Login to abc\"\r\n\r\n".toByteArray())
                    } else {
                        val uri = "rtsp://127.0.0.1:$port/cam/realmonitor?channel=1&subtype=0"
                        val ha1 = RtspClient.md5("admin:Login to abc:p@ss")
                        val expected = RtspClient.md5("$ha1:n1:" + RtspClient.md5("DESCRIBE:$uri"))
                        if (auth.contains("response=\"$expected\"") && auth.contains("uri=\"$uri\"")) {
                            val sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:trackID=0\r\n"
                            out.write("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Base: $uri/\r\nContent-Length: ${sdp.length}\r\n\r\n$sdp".toByteArray())
                        } else {
                            out.write("RTSP/1.0 401 Unauthorized\r\nCSeq: $cseq\r\nWWW-Authenticate: Digest realm=\"Login to abc\", nonce=\"n1\"\r\n\r\n".toByteArray())
                        }
                    }
                    out.flush()
                }
            }
        }
        val error = RtspClient.probe("rtsp://127.0.0.1:$port/cam/realmonitor?channel=1&subtype=0", "admin", "p@ss")
        server.close()
        assertNull(error?.message, error)
        synchronized(seen) {
            assertTrue(seen.all { !it.contains("p@ss") && !it.contains("admin@") })
        }
    }

    @Test
    fun probeReportsAuthAndUnreachable() {
        val server = ServerSocket(0)
        val port = server.localPort
        thread(isDaemon = true) {
            server.accept().use { s ->
                val reader = s.getInputStream().bufferedReader()
                repeat(2) {
                    var cseq = "1"
                    while (true) {
                        val l = reader.readLine() ?: break
                        if (l.isEmpty()) break
                        if (l.startsWith("CSeq")) cseq = l.substringAfter(":").trim()
                    }
                    s.getOutputStream().write("RTSP/1.0 401 Unauthorized\r\nCSeq: $cseq\r\nWWW-Authenticate: Digest realm=\"r\", nonce=\"n\"\r\n\r\n".toByteArray())
                }
            }
        }
        val auth = RtspClient.probe("rtsp://127.0.0.1:$port/live", "admin", "wrong")
        server.close()
        assertNotNull(auth)
        assertEquals(CameraException.Kind.AUTH, auth!!.kind)
        val closed = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        val down = RtspClient.probe("rtsp://127.0.0.1:$closed/live", "", "")
        assertEquals(CameraException.Kind.UNREACHABLE, down!!.kind)
    }

    private fun rtp(ts: Int, marker: Boolean, payload: ByteArray): ByteArray {
        val h = ByteArray(12)
        h[0] = 0x80.toByte()
        h[1] = ((if (marker) 0x80 else 0) or 96).toByte()
        h[4] = (ts ushr 24).toByte(); h[5] = (ts ushr 16).toByte(); h[6] = (ts ushr 8).toByte(); h[7] = ts.toByte()
        return h + payload
    }
}
