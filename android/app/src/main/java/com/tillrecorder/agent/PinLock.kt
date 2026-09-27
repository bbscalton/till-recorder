package com.tillrecorder.agent

import java.security.MessageDigest
import java.security.SecureRandom

internal object PinLock {
    private val pattern = Regex("^[0-9]{4,6}$")

    fun acceptable(pin: String): Boolean = pattern.matches(pin)

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(salt: ByteArray, pin: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    fun matches(salt: ByteArray, expectedHash: ByteArray, pin: String): Boolean {
        if (!acceptable(pin) || salt.isEmpty() || expectedHash.isEmpty()) return false
        return MessageDigest.isEqual(hash(salt, pin), expectedHash)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun parseHex(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
