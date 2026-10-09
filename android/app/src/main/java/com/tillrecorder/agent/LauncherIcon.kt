package com.tillrecorder.agent

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The home-screen icon is an activity alias. MainActivity itself stays
 * startable so the owner can still open the app after the icon is hidden.
 */
object LauncherIcon {
    fun apply(context: Context, store: SettingsStore) {
        val show = shouldShowLauncher(
            configured = store.isConfigured,
            watchEnabled = store.watchEnabled,
            showUntilMs = store.launcherUntil,
            nowMs = System.currentTimeMillis(),
        )
        val alias = ComponentName(context, "com.tillrecorder.agent.Launcher")
        val manager = context.packageManager
        val desired = if (show) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val current = manager.getComponentEnabledSetting(alias)
        val already = current == desired ||
            (show && current == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        if (already) return
        manager.setComponentEnabledSetting(alias, desired, PackageManager.DONT_KILL_APP)
    }
}

internal fun shouldShowLauncher(
    configured: Boolean,
    watchEnabled: Boolean,
    showUntilMs: Long,
    nowMs: Long,
): Boolean {
    if (!configured || !watchEnabled) return true
    return showUntilMs > nowMs
}
