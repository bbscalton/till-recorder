package com.tillrecorder.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class OnvifParseTest {
    @Test
    fun passwordDigestMatchesOnvifGuideExample() {
        val nonce = Base64.getDecoder().decode("LKqI6G/AikKCQrN0zqZFlg==")
        assertEquals("tuOSpGlFlIXsozq4HFNeeGeFLEI=", OnvifDiscovery.passwordDigest(nonce, "2010-09-16T07:50:45Z", "userpassword"))
    }

    @Test
    fun streamUriIsUnescaped() {
        val xml = "<s:Body><trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>rtsp://192.168.1.111:554/cam/realmonitor?channel=1&amp;subtype=0&amp;unicast=true&amp;proto=Onvif</tt:Uri></trt:MediaUri></trt:GetStreamUriResponse></s:Body>"
        assertEquals("rtsp://192.168.1.111:554/cam/realmonitor?channel=1&subtype=0&unicast=true&proto=Onvif", OnvifDiscovery.streamUri(xml))
    }

    @Test
    fun mediaXAddrIsTheMediaOne() {
        val xml = "<tds:Capabilities><tt:Analytics><tt:XAddr>http://h/onvif/analytics_service</tt:XAddr></tt:Analytics><tt:Media><tt:XAddr>http://h/onvif/media_service</tt:XAddr></tt:Media></tds:Capabilities>"
        assertEquals("http://h/onvif/media_service", OnvifDiscovery.mediaXAddr(xml))
    }

    @Test
    fun h264ProfileIsPreferredOverH265MainStream() {
        val xml = """<trt:Profiles token="MediaProfile000" fixed="true"><tt:Name>Main</tt:Name><tt:VideoEncoderConfiguration token="V0"><tt:Encoding>H265</tt:Encoding><tt:Resolution><tt:Width>2688</tt:Width><tt:Height>1520</tt:Height></tt:Resolution></tt:VideoEncoderConfiguration></trt:Profiles>
            <trt:Profiles token="MediaProfile001" fixed="true"><tt:Name>Sub</tt:Name><tt:VideoEncoderConfiguration token="V1"><tt:Encoding>H264</tt:Encoding><tt:Resolution><tt:Width>704</tt:Width><tt:Height>576</tt:Height></tt:Resolution></tt:VideoEncoderConfiguration></trt:Profiles>"""
        val profiles = OnvifDiscovery.profiles(xml)
        assertEquals(2, profiles.size)
        assertEquals("H265", profiles[0].encoding)
        assertEquals(704, profiles[1].width)
        assertEquals("MediaProfile001", OnvifDiscovery.order(profiles).first().token)
    }

    @Test
    fun allH265StillTriesSubStreamFirst() {
        val p = listOf(
            OnvifDiscovery.Profile("main", "H265", 0, 0),
            OnvifDiscovery.Profile("sub", "H265", 0, 0),
            OnvifDiscovery.Profile("third", "H265", 0, 0),
        )
        assertEquals(listOf("sub", "main", "third"), OnvifDiscovery.order(p).map { it.token })
    }

    @Test
    fun notAuthorizedFaultDetected() {
        assertTrue(OnvifDiscovery.isNotAuthorized("<env:Subcode><env:Value>ter:NotAuthorized</env:Value></env:Subcode>"))
    }

    @Test
    fun cameraClockParsed() {
        val xml = "<tt:UTCDateTime><tt:Time><tt:Hour>7</tt:Hour><tt:Minute>50</tt:Minute><tt:Second>45</tt:Second></tt:Time><tt:Date><tt:Year>2010</tt:Year><tt:Month>9</tt:Month><tt:Day>16</tt:Day></tt:Date></tt:UTCDateTime>"
        assertEquals(1284623445000L, OnvifDiscovery.cameraUtc(xml))
    }

    @Test
    fun discoveryReplyPrefersIpv4XAddr() {
        val reply = "<d:ProbeMatches><d:ProbeMatch><d:XAddrs>http://[fe80::1]/onvif/device_service http://192.168.1.111/onvif/device_service</d:XAddrs></d:ProbeMatch></d:ProbeMatches>"
        val camera = OnvifDiscovery.cameraFromReply(reply, "192.168.1.111")!!
        assertEquals("192.168.1.111", camera.host)
        assertEquals("http://192.168.1.111/onvif/device_service", camera.service)
        assertNull(OnvifDiscovery.cameraFromReply("<other/>", "1.2.3.4"))
    }

    @Test
    fun bogusReportedHostReplaced() {
        assertEquals(
            "rtsp://192.168.1.111:554/live",
            OnvifDiscovery.sameHost("rtsp://0.0.0.0:554/live", "http://192.168.1.111/onvif/device_service"),
        )
    }
}
