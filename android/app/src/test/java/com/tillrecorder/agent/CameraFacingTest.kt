package com.tillrecorder.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraFacingTest {
    // CameraManager order on a typical tablet: back (id 0), front (id 1).
    private val both = listOf(CameraFacing.LENS_BACK, CameraFacing.LENS_FRONT)

    @Test fun frontIsPickedByDefault() = assertEquals(1, CameraFacing.pick(both, back = false))

    @Test fun backIsPickedWhenChosen() = assertEquals(0, CameraFacing.pick(both, back = true))

    @Test fun missingBackFallsBackToFront() = assertEquals(0, CameraFacing.pick(listOf(CameraFacing.LENS_FRONT), back = true))

    @Test fun missingFrontFallsBackToBack() = assertEquals(0, CameraFacing.pick(listOf(CameraFacing.LENS_BACK), back = false))

    @Test fun externalCamerasAreIgnored() {
        assertEquals(2, CameraFacing.pick(listOf(2, null, CameraFacing.LENS_FRONT), back = false))
        assertNull(CameraFacing.pick(listOf(2, null), back = false))
    }
}