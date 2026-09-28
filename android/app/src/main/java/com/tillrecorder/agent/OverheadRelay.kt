package com.tillrecorder.agent

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

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
                } catch (error: Exception) {
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
        val uri = java.net.URI(target)
        if (uri.scheme != "rtsp" && uri.scheme != "rtsps") return
        val port = if (uri.port > 0) uri.port else 554
        Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, port), 8_000)
            socket.soTimeout = 8_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            var cseq = 1
            val describe = request(output, input, "DESCRIBE $target RTSP/1.0\r\nCSeq: ${cseq++}\r\nAccept: application/sdp\r\n\r\n")
            if (!describe.contains("200")) return
            val control = controlUrl(target, describe)
            val setup = request(output, input, "SETUP $control RTSP/1.0\r\nCSeq: ${cseq++}\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n")
            if (!setup.contains("200")) return
            val session = header(setup, "Session").substringBefore(";").trim()
            if (session.isEmpty()) return
            val play = request(output, input, "PLAY $target RTSP/1.0\r\nCSeq: ${cseq++}\r\nSession: $session\r\n\r\n")
            if (!play.contains("200")) return
            Log.i(TAG, "Overhead playing")
            val depay = Depay()
            var muxer: Fmp4Muxer? = null
            var sequence = 1
            val started = System.currentTimeMillis()
            while (!Thread.currentThread().isInterrupted && System.currentTimeMillis() - started < 120_000) {
                val packet = readInterleaved(input) ?: break
                if (packet.first != 0) continue
                for (nal in depay.push(packet.second)) {
                    val type = nal[0].toInt() and 0x1f
                    if (type == 7) depay.sps = nal
                    if (type == 8) depay.pps = nal
                    if (muxer == null && depay.sps != null && depay.pps != null) {
                        muxer = Fmp4Muxer(1280, 720, 15)
                        val init = muxer.start(depay.sps!!, depay.pps!!)
                        ShopClient.uploadStream(settings, "overhead", 0, init, "avc1.42E01E")
                    }
                    val current = muxer ?: continue
                    if (type == 7 || type == 8 || type == 6) continue
                    val annex = ByteArray(nal.size + 4)
                    annex[2] = 0
                    annex[3] = 1
                    nal.copyInto(annex, 4)
                    val avcc = annexBToAvcc(annex)
                    if (avcc.isEmpty()) continue
                    val media = current.media(listOf(Fmp4Muxer.Sample(avcc, type == 5)))
                    ShopClient.uploadStream(settings, "overhead", sequence++, media, "avc1.42E01E")
                }
            }
        }
    }

    private fun controlUrl(base: String, describe: String): String {
        val line = describe.lineSequence().firstOrNull { it.startsWith("a=control:") } ?: return base
        val value = line.substringAfter("a=control:").trim()
        if (value.startsWith("rtsp")) return value
        return base.trimEnd('/') + "/" + value.trimStart('/')
    }

    private fun request(output: OutputStream, input: InputStream, text: String): String {
        output.write(text.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        val buffer = ByteArray(8192)
        val read = input.read(buffer)
        if (read <= 0) return ""
        return String(buffer, 0, read, StandardCharsets.US_ASCII)
    }

    private fun header(message: String, name: String): String {
        return message.lineSequence().firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(":")?.trim().orEmpty()
    }

    private fun readInterleaved(input: InputStream): Pair<Int, ByteArray>? {
        var lead = input.read()
        if (lead < 0) return null
        while (lead >= 0 && lead != 0x24) lead = input.read()
        if (lead < 0) return null
        val channel = input.read()
        val hi = input.read()
        val lo = input.read()
        if (channel < 0 || hi < 0 || lo < 0) return null
        val length = (hi shl 8) or lo
        if (length <= 0 || length > 1_000_000) return null
        val payload = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val n = input.read(payload, filled, length - filled)
            if (n <= 0) return null
            filled += n
        }
        return channel to payload
    }

    private class Depay {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        private var fragment = ByteArray(0)

        fun push(rtp: ByteArray): List<ByteArray> {
            if (rtp.size < 13) return emptyList()
            val nalType = rtp[12].toInt() and 0x1f
            if (nalType in 1..23) return listOf(rtp.copyOfRange(12, rtp.size))
            if (nalType != 28 || rtp.size < 15) return emptyList()
            val start = rtp[13].toInt() and 0x80 != 0
            val end = rtp[13].toInt() and 0x40 != 0
            val header = ((rtp[12].toInt() and 0xe0) or (rtp[13].toInt() and 0x1f)).toByte()
            if (start) fragment = byteArrayOf(header)
            fragment += rtp.copyOfRange(14, rtp.size)
            if (!end || fragment.isEmpty()) return emptyList()
            val nal = fragment
            fragment = ByteArray(0)
            return listOf(nal)
        }
    }

    companion object {
        private const val TAG = "TillRecorder"
    }
}
