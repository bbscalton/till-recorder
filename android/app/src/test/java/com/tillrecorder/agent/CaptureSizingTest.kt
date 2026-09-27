package com.tillrecorder.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureSizingTest {
    @Test
    fun scalesAFullHdRegisterDownToTheLongEdge() {
        assertEquals(960 to 528, scaledCaptureSize(1920, 1080, 960))
    }

    @Test
    fun doesNotEnlargeASmallerScreen() {
        assertEquals(800 to 480, scaledCaptureSize(800, 480, 960))
    }

    @Test
    fun keepsPortraitInProportion() {
        assertEquals(592 to 960, scaledCaptureSize(800, 1280, 960))
    }
}
