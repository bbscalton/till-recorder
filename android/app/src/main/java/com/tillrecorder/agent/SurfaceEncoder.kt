package com.tillrecorder.agent

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * H.264 surface encoder. Live output is one-second fragmented MP4 pieces.
 * A clip copies the same samples into a normal mp4 (ftyp + moov) from a keyframe.
 * Microphone audio is added to the file when the mic is free. Live stays video.
 */
class SurfaceEncoder(
    private val width: Int,
    private val height: Int,
    private val bitRate: Int,
    private val rotationDegrees: Int,
    private val captureMic: Boolean,
    private val onChunk: (sequence: Int, bytes: ByteArray, codec: String) -> Unit,
) {
    private val lock = Any()
    private val thread = HandlerThread("till-encoder")
    private var videoCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var fmp4: Fmp4Muxer? = null
    private var codecName = "avc1.42E01E"
    private var initSent = false
    private var nextSequence = 1
    private var outputFormat: MediaFormat? = null
    private val batch = ArrayList<Fmp4Muxer.Sample>()
    private var lastVideoPts = -1L
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var armRecording = false
    private var recordFile: File? = null
    private var recordRequestedAt = 0L
    private var videoSamples = 0
    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    @Volatile private var audioFormat: MediaFormat? = null
    @Volatile private var audioFailed = !captureMic
    @Volatile private var released = false

    fun start(): Surface {
        thread.start()
        val handler = Handler(thread.looper)
        val callback = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                val chunk = synchronized(lock) {
                    outputFormat = format
                    initFrom(format)
                }
                if (chunk != null) onChunk(chunk.seq, chunk.bytes, chunk.codec)
            }

            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                Log.w(TAG, "Video encoder error", error)
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val chunk = try {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer == null || info.size <= 0) {
                        null
                    } else {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(bytes)
                        synchronized(lock) { ingest(bytes, info) }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Video frame failed", error)
                    null
                }
                codec.releaseOutputBuffer(index, false)
                if (chunk != null) onChunk(chunk.seq, chunk.bytes, chunk.codec)
            }
        }
        val codec = try {
            buildCodec(handler, callback, true)
        } catch (error: Exception) {
            Log.w(TAG, "Baseline profile was not accepted", error)
            buildCodec(handler, callback, false)
        }
        videoCodec = codec
        val surface = codec.createInputSurface()
        inputSurface = surface
        codec.start()
        startMic()
        Log.i(TAG, "Encoder ${width}x${height} at ${RecorderConfig.FRAME_RATE} fps")
        return surface
    }

    /** Draws one captured frame onto the encoder. Same surface path as a clip writer. */
    fun draw(bitmap: Bitmap) {
        val target = inputSurface ?: return
        if (released) return
        val canvas: Canvas = target.lockHardwareCanvas()
        try {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), null)
        } finally {
            target.unlockCanvasAndPost(canvas)
        }
    }

    fun openFile(file: File): Boolean {
        synchronized(lock) {
            if (released || inputSurface == null || muxer != null) return false
            recordFile = file
            armRecording = true
            recordRequestedAt = SystemClock.elapsedRealtime()
            videoSamples = 0
        }
        try {
            videoCodec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (error: Exception) {
            Log.w(TAG, "Could not request a keyframe", error)
        }
        return true
    }

    fun closeFile(): Boolean {
        val current: MediaMuxer?
        val count: Int
        val file: File?
        synchronized(lock) {
            armRecording = false
            current = muxer
            file = recordFile
            count = videoSamples
            muxer = null
            muxerStarted = false
            recordFile = null
            videoTrack = -1
            audioTrack = -1
            videoSamples = 0
        }
        if (current == null || file == null) return false
        return try {
            current.stop()
            current.release()
            count >= 8 && file.length() > 1024
        } catch (error: Exception) {
            try { current.release() } catch (_: Exception) {}
            Log.w(TAG, "Clip was too short", error)
            false
        }
    }

    fun release() {
        released = true
        closeFile()
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        audioCodec = null
        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        thread.quitSafely()
    }

    private fun buildCodec(handler: Handler, callback: MediaCodec.Callback, withProfile: Boolean): MediaCodec {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.setCallback(callback, handler)
        try {
            codec.configure(videoFormat(withProfile), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (error: Exception) {
            codec.release()
            throw error
        }
        return codec
    }

    private fun videoFormat(withProfile: Boolean): MediaFormat {
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, RecorderConfig.FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
            if (withProfile) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }
        }
    }

    private fun startMic() {
        if (!captureMic || released) return
        try {
            val min = AudioRecord.getMinBufferSize(
                RecorderConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (min <= 0) {
                audioFailed = true
                return
            }
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RecorderConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                min * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                audioFailed = true
                return
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                RecorderConfig.SAMPLE_RATE,
                1
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, RecorderConfig.AUDIO_BITRATE)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, min * 2)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            record.startRecording()
            audioRecord = record
            audioCodec = encoder
            audioFailed = false
            Thread({ pumpMic(record, encoder) }, "till-mic").start()
        } catch (error: Exception) {
            audioFailed = true
            Log.w(TAG, "Microphone unavailable, recording video only", error)
        }
    }

    private fun pumpMic(record: AudioRecord, encoder: MediaCodec) {
        val buffer = ByteArray(2048)
        val info = MediaCodec.BufferInfo()
        try {
            while (!released) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) break
                val inIndex = encoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    encoder.getInputBuffer(inIndex)?.let { input ->
                        input.clear()
                        input.put(buffer, 0, read)
                    }
                    encoder.queueInputBuffer(inIndex, 0, read.coerceAtLeast(0), System.nanoTime() / 1000, 0)
                }
                while (!released) {
                    val outIndex = encoder.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        audioFormat = encoder.outputFormat
                        continue
                    }
                    if (outIndex < 0) break
                    val out = encoder.getOutputBuffer(outIndex)
                    if (out != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val copy = ByteArray(info.size)
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        out.get(copy)
                        synchronized(lock) { writeAudio(copy, info.presentationTimeUs, info.flags) }
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                }
            }
        } catch (error: Exception) {
            audioFailed = true
            Log.w(TAG, "Microphone stopped", error)
        }
    }

    private fun ingest(bytes: ByteArray, info: MediaCodec.BufferInfo): Chunk? {
        if (released) return null
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            if (initSent) return null
            val sps = splitAnnexB(bytes).firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 7 }
            val pps = splitAnnexB(bytes).firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 8 }
            if (sps == null || pps == null) return null
            return emitInit(sps, pps)
        }
        val avcc = annexBToAvcc(bytes)
        if (avcc.isEmpty() || !initSent) return null
        noteEncodedRate()
        maybeRequestSync()
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val ticks = ticksFor(info.presentationTimeUs)
        maybeWriteVideo(bytes, info.presentationTimeUs, info.flags)
        if (key && batch.isNotEmpty()) {
            val flushed = flushBatch()
            batch.add(Fmp4Muxer.Sample(avcc, true, ticks))
            return flushed
        }
        batch.add(Fmp4Muxer.Sample(avcc, key, ticks))
        if (batch.size >= RecorderConfig.FRAME_RATE) return flushBatch()
        return null
    }

    private fun maybeWriteVideo(annexB: ByteArray, pts: Long, flags: Int) {
        if (!armRecording && muxer == null) return
        if (muxer == null) {
            val key = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            if (!key) return
            val waited = SystemClock.elapsedRealtime() - recordRequestedAt
            if (!audioFailed && audioFormat == null && waited < 500) return
            if (audioFormat == null) audioFailed = true
            if (!startMuxer()) return
        }
        val current = muxer ?: return
        if (!muxerStarted || videoTrack < 0) return
        val sample = MediaCodec.BufferInfo()
        sample.set(0, annexB.size, pts, flags)
        try {
            current.writeSampleData(videoTrack, ByteBuffer.wrap(annexB), sample)
            videoSamples += 1
        } catch (error: Exception) {
            Log.w(TAG, "Video sample dropped", error)
        }
    }

    private fun writeAudio(data: ByteArray, pts: Long, flags: Int) {
        val current = muxer ?: return
        if (!muxerStarted || audioTrack < 0) return
        val sample = MediaCodec.BufferInfo()
        sample.set(0, data.size, pts, flags)
        try {
            current.writeSampleData(audioTrack, ByteBuffer.wrap(data), sample)
        } catch (error: Exception) {
            Log.w(TAG, "Audio sample dropped", error)
        }
    }

    private fun startMuxer(): Boolean {
        val file = recordFile ?: return false
        val format = outputFormat ?: return false
        return try {
            val created = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                if (rotationDegrees != 0) created.setOrientationHint(rotationDegrees)
                videoTrack = created.addTrack(format)
                val audio = audioFormat
                if (audio != null && !audioFailed) audioTrack = created.addTrack(audio)
                created.start()
            } catch (error: Exception) {
                created.release()
                throw error
            }
            muxer = created
            muxerStarted = true
            armRecording = false
            true
        } catch (error: Exception) {
            Log.w(TAG, "Could not open the clip", error)
            false
        }
    }

    private fun initFrom(format: MediaFormat): Chunk? {
        if (initSent) return null
        val csd0 = format.getByteBuffer("csd-0")?.bytes() ?: return null
        val sps = splitAnnexB(csd0).firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 7 } ?: return null
        val csd1 = format.getByteBuffer("csd-1")?.bytes()
        val pps = csd1?.let { raw ->
            splitAnnexB(raw).firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 8 }
        } ?: splitAnnexB(csd0).firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 8 } ?: return null
        return emitInit(sps, pps)
    }

    private fun emitInit(sps: ByteArray, pps: ByteArray): Chunk {
        val mux = Fmp4Muxer(width, height, RecorderConfig.FRAME_RATE, rotationDegrees)
        fmp4 = mux
        codecName = avcCodecString(sps)
        initSent = true
        return Chunk(0, mux.start(sps, pps), codecName)
    }

    private fun flushBatch(): Chunk? {
        val mux = fmp4 ?: return null
        if (batch.isEmpty()) return null
        val samples = ArrayList(batch)
        batch.clear()
        val seq = nextSequence
        nextSequence += 1
        return Chunk(seq, mux.media(samples), codecName)
    }

    private var encodedFrames = 0
    private var encodedAt = 0L
    private var lastSyncAt = 0L

    private fun maybeRequestSync() {
        val now = SystemClock.elapsedRealtime()
        if (lastSyncAt != 0L && now - lastSyncAt < 1_000) return
        lastSyncAt = now
        try {
            videoCodec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Exception) {
        }
    }

    private fun noteEncodedRate() {
        val now = SystemClock.elapsedRealtime()
        if (encodedAt == 0L) encodedAt = now
        encodedFrames += 1
        val elapsed = now - encodedAt
        if (elapsed < 5_000) return
        Log.i(TAG, "Encoded ${width}x${height} %.1f fps".format(encodedFrames * 1000.0 / elapsed))
        encodedFrames = 0
        encodedAt = now
    }

    private fun ticksFor(pts: Long): Int {
        if (lastVideoPts < 0L || pts <= lastVideoPts) {
            lastVideoPts = pts
            return 90_000 / RecorderConfig.FRAME_RATE
        }
        val delta = pts - lastVideoPts
        lastVideoPts = pts
        return ((delta * 90_000L) / 1_000_000L).toInt().coerceIn(1, 90_000)
    }

    private data class Chunk(val seq: Int, val bytes: ByteArray, val codec: String)

    companion object {
        private const val TAG = "TillRecorder"
    }
}

private fun ByteBuffer.bytes(): ByteArray {
    val copy = duplicate()
    val out = ByteArray(copy.remaining())
    copy.get(out)
    return out
}
