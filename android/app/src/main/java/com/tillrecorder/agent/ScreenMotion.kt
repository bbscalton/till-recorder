package com.tillrecorder.agent

import kotlin.math.abs
import kotlin.math.min

fun changedFraction(previous: ByteArray, current: ByteArray, delta: Int = 18): Float {
    val count = min(previous.size, current.size)
    if (count == 0) return 0f
    var changed = 0
    for (index in 0 until count) {
        val difference = abs((previous[index].toInt() and 0xff) - (current[index].toInt() and 0xff))
        if (difference >= delta) changed++
    }
    return changed.toFloat() / count
}

fun recordingKind(
    cameraOn: Boolean,
    lastCameraMotionAt: Long,
    lastScreenMotionAt: Long,
    now: Long,
    quietMs: Long,
): String {
    if (!cameraOn || lastCameraMotionAt <= 0L || now - lastCameraMotionAt >= quietMs) return "screen"
    return if (lastCameraMotionAt >= lastScreenMotionAt) "camera" else "screen"
}

fun evenDimension(size: Int): Int {
    val even = size - (size and 1)
    return if (even >= 2) even else 0
}
