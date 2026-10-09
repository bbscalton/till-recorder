package com.tillrecorder.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            receive(context, intent)
        } catch (error: Exception) {
            Log.w(TAG, "Boot receiver failed", error)
        }
    }

    private fun receive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in BOOT_ACTIONS) return
        if (!userUnlocked(context)) return
        val store = SettingsStore(context)
        LauncherIcon.apply(context, store)
        val accessibilityOn = TillAccessibilityService.enabled(context)
        if (shouldAutoStartWatching(store.watchEnabled, store.isConfigured, accessibilityOn)) {
            Log.i(TAG, "Starting built-in watch from $action")
            RecordingService.startBuiltIn(context)
            return
        }
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        if (!store.remindAfterRestart || !store.isConfigured) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.remind_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val open = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(context.getString(R.string.remind_title))
            .setContentText(context.getString(R.string.remind_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(REMIND_ID, notification)
    }

    companion object {
        private const val TAG = "TillRecorder"
        private const val CHANNEL = "till_reminders"
        private const val REMIND_ID = 42
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )

        private fun userUnlocked(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 24) return true
            val user = context.getSystemService(UserManager::class.java) ?: return true
            return user.isUserUnlocked
        }
    }
}

internal fun shouldAutoStartWatching(
    watchEnabled: Boolean,
    configured: Boolean,
    accessibilityEnabled: Boolean,
): Boolean = watchEnabled && configured && accessibilityEnabled
