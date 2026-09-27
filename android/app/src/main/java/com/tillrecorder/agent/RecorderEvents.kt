package com.tillrecorder.agent

import java.util.concurrent.CopyOnWriteArrayList

object RecorderEvents {
    data class Status(
        val recording: Boolean,
        val detail: String,
        val pendingCount: Int,
    )

    @Volatile
    var current = Status(false, "Not recording", 0)
        private set

    private val listeners = CopyOnWriteArrayList<(Status) -> Unit>()

    fun publish(status: Status) {
        current = status
        listeners.forEach { listener -> listener(status) }
    }

    fun addListener(listener: (Status) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun removeListener(listener: (Status) -> Unit) {
        listeners.remove(listener)
    }
}
