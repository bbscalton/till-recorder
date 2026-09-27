package com.tillrecorder.agent

object RecorderConfig {
    const val SEGMENT_MS = 60 * 1000L
    const val QUIET_MS = 20 * 1000L
    const val LIVE_INTERVAL_MS = 2 * 1000L
    const val MOTION_FRACTION = 0.02f
    const val WATCH_LONG_EDGE = 640
    const val LONG_EDGE = 960
    const val FRAME_RATE = 10
    const val VIDEO_BITRATE = 900_000
    const val AUDIO_BITRATE = 64_000
    const val SAMPLE_RATE = 44_100
    const val MAX_PENDING_BYTES = 2L * 1024 * 1024 * 1024
}
