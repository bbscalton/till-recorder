package com.tillrecorder.agent

import android.content.Context
import android.net.Uri
import java.util.UUID

data class ShopSettings(
    val deviceId: String,
    val deviceName: String,
    val baseUrl: String,
    val token: String,
)

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE, null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(KEY_DEVICE, created).apply()
            return created
        }

    val deviceName: String
        get() = prefs.getString(KEY_NAME, "").orEmpty()

    val baseUrl: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL).orEmpty().ifBlank { DEFAULT_URL }

    val token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()

    var remindAfterRestart: Boolean
        get() = prefs.getBoolean(KEY_REMIND, true)
        set(value) {
            prefs.edit().putBoolean(KEY_REMIND, value).apply()
        }

    var watchEnabled: Boolean
        get() = prefs.getBoolean(KEY_WATCH, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WATCH, value).apply()
        }

    var launcherUntil: Long
        get() = prefs.getLong(KEY_LAUNCHER_UNTIL, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAUNCHER_UNTIL, value).apply()
        }

    /** True when the watch page's camera should be the tablet's back camera; false (default) is the front camera. */
    var backCamera: Boolean
        get() = prefs.getBoolean(KEY_BACK_CAMERA, false)
        set(value) { prefs.edit().putBoolean(KEY_BACK_CAMERA, value).apply() }

    var overheadUrl: String
        get() = prefs.getString(KEY_OVERHEAD, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_OVERHEAD, value.trim().take(300)).apply()
        }

    var overheadUser: String
        get() = prefs.getString(KEY_OVERHEAD_USER, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_OVERHEAD_USER, value.trim().take(80)).apply()
        }

    var overheadPassword: String
        get() = prefs.getString(KEY_OVERHEAD_PASSWORD, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_OVERHEAD_PASSWORD, value.take(80)).apply()
        }

    fun migrateOverheadCredentials() {
        val split = CameraAddress.split(overheadUrl)
        if (split.user.isEmpty()) return
        if (overheadUser.isEmpty()) overheadUser = split.user
        if (overheadPassword.isEmpty()) overheadPassword = split.password
        overheadUrl = split.bare
    }

    fun pullUrl(): String {
        migrateOverheadCredentials()
        return CameraAddress.embed(overheadUrl, overheadUser, overheadPassword)
    }

    fun overheadLabel(): String = CameraAddress.label(overheadUrl)

    val hasPin: Boolean
        get() {
            val salt = PinLock.parseHex(prefs.getString(KEY_PIN_SALT, "").orEmpty())
            val hash = PinLock.parseHex(prefs.getString(KEY_PIN_HASH, "").orEmpty())
            return salt != null && hash != null && salt.isNotEmpty() && hash.size == 32
        }

    fun setPin(pin: String): Boolean {
        if (!PinLock.acceptable(pin)) return false
        val salt = PinLock.newSalt()
        val hash = PinLock.hash(salt, pin)
        prefs.edit()
            .putString(KEY_PIN_SALT, PinLock.hex(salt))
            .putString(KEY_PIN_HASH, PinLock.hex(hash))
            .apply()
        return true
    }

    fun checkPin(pin: String): Boolean {
        val salt = PinLock.parseHex(prefs.getString(KEY_PIN_SALT, "").orEmpty()) ?: return false
        val hash = PinLock.parseHex(prefs.getString(KEY_PIN_HASH, "").orEmpty()) ?: return false
        return PinLock.matches(salt, hash, pin)
    }

    var uploadError: String
        get() = prefs.getString(KEY_ERROR, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_ERROR, value).apply()
        }

    val isConfigured: Boolean
        get() {
            val settings = current()
            return settings.deviceName.isNotBlank() &&
                normalizeBaseUrl(settings.baseUrl) != null &&
                settings.token.isNotBlank()
        }

    fun forgetPairing() {
        prefs.edit()
            .putString(KEY_TOKEN, "")
            .putBoolean(KEY_WATCH, false)
            .apply()
    }

    fun save(name: String, baseUrl: String, token: String, remind: Boolean) {
        prefs.edit()
            .putString(KEY_NAME, name.trim().take(80))
            .putString(KEY_URL, baseUrl.trim())
            .putString(KEY_TOKEN, token.trim())
            .putBoolean(KEY_REMIND, remind)
            .apply()
    }

    fun current(): ShopSettings {
        return ShopSettings(
            deviceId = deviceId,
            deviceName = deviceName.trim(),
            baseUrl = baseUrl.trim().trimEnd('/'),
            token = token.trim(),
        )
    }

    companion object {
        private const val PREFS = "till"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_NAME = "device_name"
        private const val KEY_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_REMIND = "remind"
        private const val KEY_ERROR = "upload_error"
        private const val KEY_WATCH = "watch_enabled"
        private const val KEY_LAUNCHER_UNTIL = "launcher_until"
        private const val KEY_BACK_CAMERA = "back_camera"
        private const val KEY_OVERHEAD = "overhead_url"
        private const val KEY_OVERHEAD_USER = "overhead_user"
        private const val KEY_OVERHEAD_PASSWORD = "overhead_password"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_HASH = "pin_hash"
        const val DEFAULT_URL = "https://till-recorder.neuereatec.workers.dev"

        fun normalizeBaseUrl(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            val uri = Uri.parse(trimmed)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank()) return null
            return trimmed
        }
    }
}
