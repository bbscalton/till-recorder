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
        get() = prefs.getString(KEY_URL, "").orEmpty()

    val token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()

    var remindAfterRestart: Boolean
        get() = prefs.getBoolean(KEY_REMIND, true)
        set(value) {
            prefs.edit().putBoolean(KEY_REMIND, value).apply()
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
