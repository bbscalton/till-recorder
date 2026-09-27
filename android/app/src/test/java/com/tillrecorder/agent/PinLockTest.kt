package com.tillrecorder.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLockTest {
    @Test
    fun acceptsFourToSixDigits() {
        assertTrue(PinLock.acceptable("1234"))
        assertTrue(PinLock.acceptable("123456"))
        assertFalse(PinLock.acceptable("123"))
        assertFalse(PinLock.acceptable("1234567"))
        assertFalse(PinLock.acceptable("12ab"))
        assertFalse(PinLock.acceptable(""))
    }

    @Test
    fun hashMatchesOnlyTheSamePin() {
        val salt = PinLock.newSalt()
        val hash = PinLock.hash(salt, "2580")
        assertTrue(PinLock.matches(salt, hash, "2580"))
        assertFalse(PinLock.matches(salt, hash, "2581"))
        assertFalse(PinLock.matches(salt, hash, "258"))
        assertFalse(hash.contentEquals("2580".toByteArray()))
    }

    @Test
    fun differentSaltsDoNotMatch() {
        val pin = "2468"
        val first = PinLock.hash(PinLock.newSalt(), pin)
        val secondSalt = PinLock.newSalt()
        assertFalse(PinLock.matches(secondSalt, first, pin))
    }

    @Test
    fun hexRoundTrip() {
        val salt = PinLock.newSalt()
        val parsed = PinLock.parseHex(PinLock.hex(salt))
        assertNotNull(parsed)
        assertTrue(salt.contentEquals(parsed))
        assertTrue(PinLock.parseHex("zz") == null)
    }
}

class BootStartTest {
    @Test
    fun startsOnlyWhenWatchingWasLeftOn() {
        assertTrue(shouldAutoStartWatching(watchEnabled = true, configured = true, accessibilityEnabled = true))
        assertFalse(shouldAutoStartWatching(watchEnabled = false, configured = true, accessibilityEnabled = true))
        assertFalse(shouldAutoStartWatching(watchEnabled = true, configured = false, accessibilityEnabled = true))
        assertFalse(shouldAutoStartWatching(watchEnabled = true, configured = true, accessibilityEnabled = false))
    }
}
