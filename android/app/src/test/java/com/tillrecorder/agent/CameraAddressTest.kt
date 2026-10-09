package com.tillrecorder.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAddressTest {
    private fun r(raw: String, user: String = "", password: String = "") = CameraAddress.resolve(raw, user, password)

    @Test
    fun bareIpBecomesOnvifDeviceService() {
        val t = r("192.168.1.111", "admin", "x")
        assertEquals(CameraAddress.Kind.ONVIF, t.kind)
        assertEquals("http://192.168.1.111/onvif/device_service", t.url)
    }

    @Test
    fun hostPortBecomesOnvifUnlessRtspPort() {
        assertEquals("http://cam.local:8000/onvif/device_service", r("cam.local:8000").url)
        val rtsp = r("192.168.1.111:554/cam/realmonitor?channel=1&subtype=1")
        assertEquals(CameraAddress.Kind.RTSP, rtsp.kind)
        assertEquals("rtsp://192.168.1.111:554/cam/realmonitor?channel=1&subtype=1", rtsp.url)
    }

    @Test
    fun httpWithoutPathGetsDeviceService() {
        assertEquals("http://10.0.0.5:8080/onvif/device_service", r("http://10.0.0.5:8080").url)
        assertEquals("http://10.0.0.5:8080/onvif/device_service", r("HTTP://10.0.0.5:8080/").url)
        assertEquals("https://10.0.0.5/onvif/device_service", r("https://10.0.0.5/onvif/device_service").url)
    }

    @Test
    fun rtspWithQueryAndCredentialsInUrl() {
        val t = r("rtsp://admin:p%40ss@192.168.1.111:554/cam/realmonitor?channel=1&subtype=0")
        assertEquals(CameraAddress.Kind.RTSP, t.kind)
        assertEquals("rtsp://192.168.1.111:554/cam/realmonitor?channel=1&subtype=0", t.url)
        assertEquals("admin", t.user)
        assertEquals("p@ss", t.password)
    }

    @Test
    fun rawSpecialCharactersInPasswordAreKept() {
        val t = r("rtsp://admin:a#b/c?d@e:f@192.168.1.111/Streaming/Channels/101")
        assertEquals("rtsp://192.168.1.111/Streaming/Channels/101", t.url)
        assertEquals("a#b/c?d@e:f", t.password)
    }

    @Test
    fun fieldsWinOverUrlCredentials() {
        val t = r("rtsp://old:old@1.2.3.4/live", "admin", "new")
        assertEquals("admin", t.user)
        assertEquals("new", t.password)
        assertEquals("rtsp://1.2.3.4/live", t.url)
    }

    @Test
    fun embedEncodesAndSplitRoundTrips() {
        val bare = "rtsp://192.168.1.111:554/cam/realmonitor?channel=1&subtype=0"
        val password = "p@ss:w/rd#%?&"
        val embedded = CameraAddress.embed(bare, "ad min", password)
        assertTrue(embedded.startsWith("rtsp://ad%20min:p%40ss%3Aw%2Frd%23%25%3F%26@192.168.1.111:554/"))
        val split = CameraAddress.split(embedded)
        assertEquals(bare, split.bare)
        assertEquals("ad min", split.user)
        assertEquals(password, split.password)
        assertEquals("192.168.1.111", CameraAddress.label(embedded))
    }

    @Test
    fun xmlEscapedAmpersandIsRepaired() {
        assertEquals(
            "rtsp://1.2.3.4:554/cam/realmonitor?channel=1&subtype=0",
            r("rtsp://1.2.3.4:554/cam/realmonitor?channel=1&amp;subtype=0").url,
        )
    }

    @Test
    fun rejectsWithClearMessage() {
        assertEquals(CameraAddress.Kind.INVALID, r("").kind)
        assertEquals(CameraAddress.Kind.INVALID, r("ftp://1.2.3.4/").kind)
        assertEquals(CameraAddress.Kind.INVALID, r("rtsp://1.2.3.4:99999/").kind)
        val tls = r("rtsps://1.2.3.4/live")
        assertEquals(CameraAddress.Kind.INVALID, tls.kind)
        assertTrue(tls.error.contains("rtsps"))
    }

    @Test
    fun ipv6Host() {
        val t = r("rtsp://[fe80::1]:554/live")
        assertEquals(CameraAddress.Kind.RTSP, t.kind)
        assertEquals("fe80::1", CameraAddress.parse(t.url)?.host)
    }

    @Test
    fun subStreamForms() {
        assertEquals("rtsp://h:554/cam/realmonitor?channel=1&subtype=1", CameraAddress.subStream("rtsp://h:554/cam/realmonitor?channel=1&subtype=0"))
        assertEquals("rtsp://h/Streaming/Channels/102", CameraAddress.subStream("rtsp://h/Streaming/Channels/101"))
        assertEquals(null, CameraAddress.subStream("rtsp://h/live"))
    }
}
