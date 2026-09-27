package com.tillrecorder.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * Writes the register screen and microphone into one clip without the system share dialog.
 * Frames are drawn by Till Recorder. The microphone is recorded only while a clip is open.
 */
class ClipWriter(
    private val file: File,
    private val width: Int,
    private val height: Int,
) {
    private val lock = Any()
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var videoEos = false
    private var audioEos = false
    private val videoCodec: MediaCodec
    private val audioCodec: MediaCodec
    private val surface: Surface
    private val audioRecord: AudioRecord
    private val dest = Rect(0, 0, width, height)
    @Volatile private var running = true
    private var audioThread: Thread? = null
    private var started = false

    init {
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        videoFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        )
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, RecorderConfig.VIDEO_BITRATE)
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 2)
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = videoCodec.createInputSurface()

        val audioFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            RecorderConfig.SAMPLE_RATE,
            1,
        )
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, RecorderConfig.AUDIO_BITRATE)
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val minBuffer = AudioRecord.getMinBufferSize(
            RecorderConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            RecorderConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer.coerceAtLeast(2048) * 2,
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            releaseQuietly()
            error("Microphone is not available")
        }
    }

    fun start() {
        synchronized(lock) {
            videoCodec.start()
            audioCodec.start()
            audioRecord.startRecording()
            started = true
        }
        val thread = Thread({ recordAudio() }, "till-clip-audio")
        audioThread = thread
        thread.start()
    }

    fun draw(bitmap: Bitmap) {
        if (!running) return
        val canvas: Canvas = surface.lockHardwareCanvas()
        try {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, null, dest, null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        synchronized(lock) { drain(videoCodec, video = true, untilEos = false) }
    }

    fun finish(): Boolean {
        running = false
        try {
            audioThread?.join(2_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(lock) {
            signalAudioEnd()
            try {
                videoCodec.signalEndOfInputStream()
            } catch (error: Exception) {
                Log.w(TAG, "Video end failed", error)
            }
            val deadline = System.nanoTime() + 3_000_000_000L
            while ((!videoEos || !audioEos) && System.nanoTime() < deadline) {
                drain(videoCodec, video = true, untilEos = false)
                drain(audioCodec, video = false, untilEos = false)
            }
            val ok = muxerStarted
            releaseQuietly()
            return ok && file.exists() && file.length() > 1024
        }
    }

    private fun recordAudio() {
        val buffer = ByteArray(2048)
        var samples = 0L
        while (running) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            val index = audioCodec.dequeueInputBuffer(10_000)
            if (index < 0) continue
            val input = audioCodec.getInputBuffer(index) ?: continue
            input.clear()
            input.put(buffer, 0, read)
            val timeUs = samples * 1_000_000L / RecorderConfig.SAMPLE_RATE
            samples += read / 2
            audioCodec.queueInputBuffer(index, 0, read, timeUs, 0)
            synchronized(lock) { drain(audioCodec, video = false, untilEos = false) }
        }
    }

    private fun signalAudioEnd() {
        val index = audioCodec.dequeueInputBuffer(10_000)
        if (index < 0) {
            audioEos = true
            return
        }
        audioCodec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private fun drain(codec: MediaCodec, video: Boolean, untilEos: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val track = muxer.addTrack(codec.outputFormat)
                    if (video) videoTrack = track else audioTrack = track
                    if (videoTrack >= 0 && audioTrack >= 0 && !muxerStarted) {
                        muxer.start()
                        muxerStarted = true
                    }
                }
                index >= 0 -> {
                    val encoded = codec.getOutputBuffer(index)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (muxerStarted && encoded != null && info.size > 0 && !config) {
                        val track = if (video) videoTrack else audioTrack
                        if (track >= 0) muxer.writeSampleData(track, encoded, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) {
                        if (video) videoEos = true else audioEos = true
                        return
                    }
                }
                else -> return
            }
        }
    }

    private fun releaseQuietly() {
        try { if (started) audioRecord.stop() } catch (_: Exception) {}
        try { audioRecord.release() } catch (_: Exception) {}
        try { videoCodec.stop() } catch (_: Exception) {}
        try { videoCodec.release() } catch (_: Exception) {}
        try { audioCodec.stop() } catch (_: Exception) {}
        try { audioCodec.release() } catch (_: Exception) {}
        try { surface.release() } catch (_: Exception) {}
        try {
            if (muxerStarted) muxer.stop()
        } catch (error: Exception) {
            Log.w(TAG, "Clip close failed", error)
            file.delete()
        }
        try { muxer.release() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "TillRecorder"
    }
}
