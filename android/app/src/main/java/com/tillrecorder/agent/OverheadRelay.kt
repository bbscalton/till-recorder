package com.tillrecorder.agent

import android.os.SystemClock
import android.util.Log

/**
 * Pulls an RTSP camera on the store network and uploads it as the overhead picture.
 * Failures stay on this thread and do not stop screen capture.
 */
class OverheadRelay {
    @Volatile private var url = ""
    private var worker: Thread? = null

    fun apply(settings: ShopSettings, next: String) {
        val target = next.trim()
        if (target == url && worker?.isAlive == true) return
        url = target
        worker?.interrupt()
        if (target.isEmpty()) return
        val thread = Thread({
            while (!Thread.currentThread().isInterrupted && url == target) {
                try {
                    Log.i(TAG, "Overhead connect")
                    pull(settings, target)
                } catch (error: CameraException) {
                    lastError = error.message.orEmpty()
                    Log.w(TAG, "Overhead ${error.kind}: ${error.message}")
                } catch (error: Exception) {
                    lastError = "Overhead camera stopped (${error.javaClass.simpleName})."
                    Log.w(TAG, "Overhead ${error.javaClass.simpleName}")
                }
                try { Thread.sleep(15_000) } catch (_: InterruptedException) { return@Thread }
            }
        }, "till-overhead")
        thread.isDaemon = true
        worker = thread
        thread.start()
    }

    private fun pull(settings: ShopSettings, target: String) {
        val parts = CameraAddress.split(target)
        RtspClient(parts.bare, parts.user, parts.password).use { client ->
            client.connect()
            val track = client.describe()
            client.setup(track)
            client.play(track)
            Log.i(TAG, "Overhead playing")
            lastError = ""
            val assembler = RtpH264Assembler()
            track.sps?.let { assembler.sps = it }
            track.pps?.let { assembler.pps = it }
            var muxer: Fmp4Muxer? = null
            var codec = "avc1.42E01E"
            var sequence = 1
            val batch = ArrayList<Fmp4Muxer.Sample>()
            var batchStarted = 0L
            var lastTs = -1L
            var lastKeepAlive = SystemClock.elapsedRealtime()
            val keepAliveMs = (client.sessionTimeoutSec * 1000L / 2).coerceIn(5_000L, 30_000L)
            val started = SystemClock.elapsedRealtime()
            while (!Thread.currentThread().isInterrupted && url == target && SystemClock.elapsedRealtime() - started < 600_000) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastKeepAlive > keepAliveMs) {
                    client.keepAlive()
                    lastKeepAlive = now
                }
                val packet = client.readPacket() ?: break
                if (packet.first != client.videoChannel) continue
                for (unit in assembler.push(packet.second)) {
                    if (muxer == null) {
                        val sps = assembler.sps
                        val pps = assembler.pps
                        if (sps == null || pps == null || !unit.keyframe) continue
                        muxer = Fmp4Muxer(1280, 720, 15)
                        codec = avcCodecString(sps)
                        ShopClient.uploadStream(settings, "overhead", 0, muxer.start(sps, pps), codec)
                    }
                    val ticks = if (lastTs < 0) 6000 else ((unit.timestamp - lastTs) and 0xffffffffL).toInt().coerceIn(1, 90_000)
                    lastTs = unit.timestamp
                    batch.add(Fmp4Muxer.Sample(RtpH264Assembler.avcc(unit.nals), unit.keyframe, ticks))
                    if (batchStarted == 0L) batchStarted = SystemClock.elapsedRealtime()
                    if (batch.size >= 15 || SystemClock.elapsedRealtime() - batchStarted >= 500) {
                        val media = muxer.media(ArrayList(batch))
                        batch.clear()
                        batchStarted = 0L
                        ShopClient.uploadStream(settings, "overhead", sequence++, media, codec)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "TillRecorder"

        /** Last relay failure in plain words (no address or password), empty while playing. */
        @Volatile var lastError: String = ""
    }
}
