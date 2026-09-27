package com.tillrecorder.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisterLockTest {
    @Test
    fun websiteLockEngagesUntilPinOrUnlock() {
        val state = RegisterLockState()
        state.apply(locked = false, lockSeq = 0)
        assertFalse(state.engaged)

        state.apply(locked = true, lockSeq = 1)
        assertTrue(state.engaged)

        state.clearFromPin()
        assertFalse(state.engaged)
        assertTrue(state.shouldPushClear())
        assertEquals(1, state.pendingClearSeq)

        state.apply(locked = true, lockSeq = 1)
        assertFalse(state.engaged)

        state.apply(locked = false, lockSeq = 2)
        assertFalse(state.engaged)
        assertFalse(state.shouldPushClear())

        state.apply(locked = true, lockSeq = 3)
        assertTrue(state.engaged)
    }
}
