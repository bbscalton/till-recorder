package com.tillrecorder.agent

internal class RegisterLockState {
    var engaged: Boolean = false
        private set
    var currentSeq: Int = 0
        private set
    var pendingClearSeq: Int = -1
        private set

    fun apply(locked: Boolean, lockSeq: Int) {
        currentSeq = lockSeq
        if (!locked) {
            engaged = false
            pendingClearSeq = -1
            return
        }
        if (pendingClearSeq == lockSeq) {
            engaged = false
            return
        }
        engaged = true
    }

    fun clearFromPin() {
        pendingClearSeq = currentSeq
        engaged = false
    }

    fun shouldPushClear(): Boolean {
        return pendingClearSeq >= 0 && pendingClearSeq == currentSeq && !engaged
    }
}

internal object RegisterLock {
    private val state = RegisterLockState()

    fun isEngaged(): Boolean = state.engaged

    fun currentSeq(): Int = state.currentSeq

    fun pendingClearSeq(): Int = state.pendingClearSeq

    fun apply(locked: Boolean, lockSeq: Int) {
        state.apply(locked, lockSeq)
    }

    fun clearFromPin() {
        state.clearFromPin()
    }

    fun shouldPushClear(): Boolean = state.shouldPushClear()
}
