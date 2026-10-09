package com.tillrecorder.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFieldSyncTest {
    @Test
    fun typedAddressSurvivesRefreshAfterFocusMoves() {
        // 1.5.13 bug: the IP was typed, focus moved to Username, and the 3 s refresh put back the saved "" address.
        assertFalse(CameraFieldSync.shouldReplace(focused = false, edited = true, shown = "192.168.1.113", saved = ""))
    }

    @Test
    fun focusedBoxIsNeverReplaced() {
        assertFalse(CameraFieldSync.shouldReplace(focused = true, edited = false, shown = "192.168.1.11", saved = ""))
    }

    @Test
    fun untouchedBoxShowsSavedCamera() {
        assertTrue(CameraFieldSync.shouldReplace(focused = false, edited = false, shown = "", saved = "rtsp://192.168.1.113:554/cam/realmonitor?channel=1&subtype=0"))
        assertFalse(CameraFieldSync.shouldReplace(focused = false, edited = false, shown = "same", saved = "same"))
    }

    @Test
    fun discoveryKeepsEveryCameraOnce() {
        val found = OnvifDiscovery.Found()
        fun reply(ip: String, uuid: String) =
            "<d:ProbeMatch><a:EndpointReference><a:Address>urn:uuid:$uuid</a:Address></a:EndpointReference>" +
                "<d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/hardware/IPC-HFW1230S onvif://www.onvif.org/name/Dahua</d:Scopes>" +
                "<d:XAddrs>http://$ip/onvif/device_service</d:XAddrs></d:ProbeMatch>"
        for (round in 1..3) {
            for (last in listOf(118, 111, 113, 112, 115, 114, 117, 116)) {
                // Cloned firmware: every camera reports the same endpoint id.
                found.add(reply("192.168.1.$last", "same-id"), "192.168.1.$last")
            }
        }
        assertEquals((111..118).map { "192.168.1.$it" }, found.cameras.map { it.host })
        assertEquals("Dahua IPC-HFW1230S", found.cameras.first().name)
    }
}