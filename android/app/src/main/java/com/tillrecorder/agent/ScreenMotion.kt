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
