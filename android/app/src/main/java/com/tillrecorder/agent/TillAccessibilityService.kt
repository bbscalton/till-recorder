package com.tillrecorder.agent

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One-time screen watch for this register. The shop turns it on in Accessibility once.
 * After that it stays on, including after a restart. A notice stays visible while watching.
 */
class TillAccessibilityService : AccessibilityService() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        instance = this
        val store = SettingsStore(this)
        if (store.watchEnabled && store.isConfigured) {
            RecordingService.startBuiltIn(this)
        }
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!RegisterLock.isEngaged()) return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.className == RegisterLockActivity::class.java.name) return
        RegisterLockActivity.show(this)
    }

    override fun onInterrupt() = Unit

    fun bringLockForward(intent: Intent) {
        startActivity(intent)
    }

    fun capture(onBitmap: (Bitmap?, Int) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            onBitmap(null, 1)
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, executor, screenshotCallback(onBitmap))
    }

    @RequiresApi(30)
    private fun screenshotCallback(onBitmap: (Bitmap?, Int) -> Unit): TakeScreenshotCallback {
        return object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                onBitmap(bitmapFrom(screenshot), 0)
            }

            override fun onFailure(errorCode: Int) {
                onBitmap(null, errorCode)
            }
        }
    }

    companion object {
        @Volatile
        var instance: TillAccessibilityService? = null
            private set

        fun enabled(context: Context): Boolean {
            val expected = ComponentName(context, TillAccessibilityService::class.java).flattenToString()
            val setting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return setting.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        @RequiresApi(30)
        private fun bitmapFrom(screenshot: AccessibilityService.ScreenshotResult): Bitmap? {
            val buffer: HardwareBuffer = screenshot.hardwareBuffer
            return try {
                val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: return null
                val copy = hardware.copy(Bitmap.Config.ARGB_8888, false)
                hardware.recycle()
                copy
            } catch (_: Exception) {
                null
            } finally {
                buffer.close()
            }
        }
    }
}
