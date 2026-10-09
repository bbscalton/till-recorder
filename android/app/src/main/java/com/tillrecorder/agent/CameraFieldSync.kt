package com.tillrecorder.agent

/**
 * Decides when the overhead camera boxes may be refilled from the saved settings.
 * The status screen refreshes every few seconds; it must never wipe an address the user typed but has not saved.
 */
object CameraFieldSync {
    fun shouldReplace(focused: Boolean, edited: Boolean, shown: String, saved: String): Boolean {
        if (focused || edited) return false
        return shown != saved
    }
}