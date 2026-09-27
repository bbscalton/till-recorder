package com.tillrecorder.agent

import kotlin.math.max
import kotlin.math.min

fun scaledCaptureSize(srcW: Int, srcH: Int, longEdge: Int): Pair<Int, Int> {
    if (srcW <= 0 || srcH <= 0) return 960 to 544
    val scale = min(1f, longEdge.toFloat() / max(srcW, srcH).toFloat())
    val width = max(16, ((srcW * scale).toInt() / 16) * 16)
    val height = max(16, ((srcH * scale).toInt() / 16) * 16)
    return width to height
}
