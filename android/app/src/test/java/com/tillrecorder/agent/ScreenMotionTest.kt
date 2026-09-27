package com.tillrecorder.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMotionTest {
    @Test
    fun aStillScreenIsNotActivity() {
        val frame = ByteArray(100) { 40 }
        assertEquals(0f, changedFraction(frame, frame.copyOf()))
    }

    @Test
    fun aSmallClockChangeStaysBelowTheActivityLine() {
        val previous = ByteArray(100) { 40 }
        val current = previous.copyOf()
        current[0] = 80
        assertTrue(changedFraction(previous, current) < 0.02f)
    }

    @Test
    fun aRegisterScreenChangeCountsAsActivity() {
        val previous = ByteArray(100) { 20 }
        val current = ByteArray(100) { 200.toByte() }
        assertTrue(changedFraction(previous, current) > 0.02f)
    }
}
