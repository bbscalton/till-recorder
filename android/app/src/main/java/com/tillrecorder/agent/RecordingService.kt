package com.tillrecorder.agent

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private lateinit var store: SettingsStore
    private var projection: MediaProjection? = null
    private var systemStoppedProjection = false
    private var running = false
    private val stopRequested = AtomicBoolean(false)
    private var active: Active? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val watchThread = HandlerThread("till-watch")
    private var watchHandler: Handler? = null
    private var reader: ImageReader? = null
    private var watchDisplay: VirtualDisplay? = null
    private var previousLuma: ByteArray? = null
    @Volatile private var lastMotionAt = 0L
    private var lastSampleAt = 0L
    private var lastLiveAt = 0L
    private var segmentBusy = false
    private var frontCamera: FrontCamera? = null
    private var cameraOn = false
    private var cameraPermissionNoted = false
    private var cameraWake: PowerManager.WakeLock? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            lastMotionAt = 0L
            previousLuma = null
            handler.post {
                if (active != null) endSegment(continueRecording = false)
                publish("Screen is off. Waiting until the register is used.")
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            systemStoppedProjection = true
            requestStop("Recording stopped from the tablet's recording notice.")
        }
    }

    private val rotate = Runnable {
        val stillActive = System.currentTimeMillis() - lastMotionAt < RecorderConfig.QUIET_MS
        if (running && !stopRequested.get() && stillActive) {
            endSegment(continueRecording = true)
        } else {
            endSegment(continueRecording = false)
            if (running) publish("Watching. A clip starts when the screen changes.")
        }
    }

    private val beat = object : Runnable {
        override fun run() {
            if (!running || stopRequested.get()) return
            UploadWorker.enqueue(this@RecordingService)
            handler.postDelayed(this, 60_000)
        }
    }

    private val command = object : Runnable {
        override fun run() {
            if (!running || stopRequested.get()) return
            val settings = store.current()
            val capturing = active != null
            net.execute {
                val wanted = ShopClient.heartbeat(settings, capturing)
                if (wanted != null) handler.post { applyCamera(wanted) }
            }
            handler.postDelayed(this, 5_000)
        }
    }

    private val flush = object : Runnable {
        override fun run() {
            if (running) return
            val pending = RecordingFiles.pendingCount(this@RecordingService)
            if (pending > 0 && System.currentTimeMillis() < flushUntil) {
                UploadWorker.enqueue(this@RecordingService)
                notify(notification("Sending the last clips to Cloudflare"))
                handler.postDelayed(this, 3_000)
            } else {
                teardown()
            }
        }
    }

    private var flushUntil = 0L

    override fun onCreate() {
        super.onCreate()
        store = SettingsStore(this)
        watchThread.start()
        watchHandler = Handler(watchThread.looper)
        val screenFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, screenFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, screenFilter)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop("Recording stopped.")
            ACTION_START -> startFrom(intent)
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        running = false
        endSegment(continueRecording = false)
        releaseFront()
        releaseWatch()
        releaseProjection()
        releaseWake()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        watchThread.quitSafely()
        net.shutdownNow()
        super.onDestroy()
    }

    private fun startFrom(intent: Intent) {
        if (running) return
        handler.removeCallbacks(flush)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return
        }
        // The projection is only valid after this service is in the foreground.
        startInForeground("Starting recording")
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val created = manager.getMediaProjection(resultCode, data)
            if (created == null) {
                fail("The tablet did not allow screen capture.")
                return
            }
            projection = created
            systemStoppedProjection = false
            created.registerCallback(projectionCallback, handler)
            stopRequested.set(false)
            running = true
            publish("Watching. A clip starts when the screen changes.")
            handler.post { startWatching() }
            handler.post(command)
            handler.postDelayed(beat, 60_000)
        } catch (error: Exception) {
            Log.e(TAG, "Could not start capture", error)
            fail(error.message ?: "Could not start recording")
        }
    }

    private fun startWatching() {
        if (!running || stopRequested.get()) return
        val currentProjection = projection ?: return
        releaseWatch()
        val (width, height, dpi) = watchSize()
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastSampleAt >= 500) {
                lastSampleAt = now
                val luma = sampleLuma(image)
                val previous = previousLuma
                previousLuma = luma
                val moved = previous != null &&
                    changedFraction(previous, luma) >= RecorderConfig.MOTION_FRACTION
                if (moved) lastMotionAt = now
                val activeNow = now - lastMotionAt < RecorderConfig.QUIET_MS
                val jpeg = if (activeNow && now - lastLiveAt >= RecorderConfig.LIVE_INTERVAL_MS) {
                    lastLiveAt = now
                    jpegBytes(image)
                } else {
                    null
                }
                if (jpeg != null) {
                    val settings = store.current()
                    net.execute { ShopClient.uploadLive(settings, jpeg) }
                }
                handler.post { onMotion(activeNow) }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Screen sample failed", error)
            } finally {
                image.close()
            }
        }, watchHandler)
        watchDisplay = currentProjection.createVirtualDisplay(
            "TillRecorderWatch",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )
    }

    private fun onMotion(activeNow: Boolean) {
        if (!running || stopRequested.get() || segmentBusy) return
        if (activeNow && active == null) {
            segmentBusy = true
            beginSegment()
            segmentBusy = false
        } else if (!activeNow && active != null) {
            val started = active?.started ?: return
            if (System.currentTimeMillis() - started < 8_000) return
            segmentBusy = true
            endSegment(continueRecording = false)
            segmentBusy = false
            publish("Watching. A clip starts when the screen changes.")
        }
    }

    private fun releaseWatch() {
        try { watchDisplay?.release() } catch (_: Exception) {}
        watchDisplay = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        previousLuma = null
    }

    private fun beginSegment() {
        if (!running || stopRequested.get()) return
        val currentProjection = projection ?: return
        val dir = RecordingFiles.pendingDir(this)
        RecordingFiles.trim(dir, RecorderConfig.MAX_PENDING_BYTES)
        dir.listFiles { file -> file.name.startsWith("partial-") }?.forEach { it.delete() }
        val (width, height, dpi) = captureSize()
        val started = System.currentTimeMillis()
        val file = File(dir, "partial-$started.mp4")
        val recorder = newRecorder()
        var display: VirtualDisplay? = null
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setVideoSize(width, height)
            recorder.setVideoFrameRate(RecorderConfig.FRAME_RATE)
            recorder.setVideoEncodingBitRate(RecorderConfig.VIDEO_BITRATE)
            recorder.setAudioEncodingBitRate(RecorderConfig.AUDIO_BITRATE)
            recorder.setAudioSamplingRate(RecorderConfig.SAMPLE_RATE)
            recorder.setAudioChannels(1)
            recorder.setOutputFile(file.absolutePath)
            recorder.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Recorder error $what extra=$extra")
                requestStop("The tablet stopped the recording. Open Till Recorder and start it again.")
            }
            recorder.prepare()
            display = currentProjection.createVirtualDisplay(
                "TillRecorder",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null
            )
            recorder.start()
            active = Active(recorder, display, file, started)
            holdWake()
            handler.removeCallbacks(rotate)
            handler.postDelayed(rotate, RecorderConfig.SEGMENT_MS)
            publish("Recording ${store.deviceName}")
            UploadWorker.enqueue(this)
        } catch (error: Exception) {
            Log.e(TAG, "Segment failed", error)
            try { display?.release() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            file.delete()
            requestStop("Could not record the screen. ${error.message ?: ""}".trim())
        }
    }

    private fun endSegment(continueRecording: Boolean) {
        handler.removeCallbacks(rotate)
        val current = active ?: run {
            if (continueRecording && running && !stopRequested.get()) beginSegment()
            return
        }
        active = null
        var ended = System.currentTimeMillis()
        var saved = false
        try {
            current.recorder.stop()
            saved = true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Short segment discarded", error)
            current.file.delete()
            ended = -1
        }
        try { current.display.release() } catch (_: Exception) {}
        try { current.recorder.release() } catch (_: Exception) {}
        if (saved && ended > current.started && current.file.exists() && current.file.length() > 1024) {
            val folder = current.file.parentFile
            val finalFile = File(folder, "${current.started}-$ended.mp4")
            if (current.file.renameTo(finalFile)) {
                File(folder, "${current.started}-$ended.json").writeText(
                    JSONObject()
                        .put("startedAtMs", current.started)
                        .put("endedAtMs", ended)
                        .toString()
                )
                UploadWorker.enqueue(this)
            }
        } else if (current.file.exists()) {
            current.file.delete()
        }
        if (continueRecording && running && !stopRequested.get()) beginSegment()
    }

    private fun requestStop(reason: String) {
        if (!stopRequested.compareAndSet(false, true)) return
        running = false
        handler.removeCallbacks(beat)
        handler.removeCallbacks(command)
        handler.post {
            endSegment(continueRecording = false)
            releaseFront()
            releaseWatch()
            releaseProjection()
            releaseWake()
            net.execute { ShopClient.heartbeat(store.current(), false) }
            publish(reason)
            flushUntil = System.currentTimeMillis() + 2 * 60 * 1000L
            handler.post(flush)
        }
    }

    private fun fail(message: String) {
        stopRequested.set(true)
        running = false
        publish(message)
        releaseFront()
        releaseWatch()
        releaseProjection()
        releaseWake()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseProjection() {
        val current = projection ?: return
        projection = null
        try { current.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        if (!systemStoppedProjection) {
            try { current.stop() } catch (_: Exception) {}
        }
    }

    private fun publish(detail: String) {
        val pending = RecordingFiles.pendingCount(this)
        RecorderEvents.publish(
            RecorderEvents.Status(
                recording = running && !stopRequested.get(),
                detail = detail,
                pendingCount = pending,
            )
        )
        if (running && !stopRequested.get()) notify(notification(detail))
    }

    @Suppress("DEPRECATION")
    private fun captureSize(): Triple<Int, Int, Int> {
        val metrics = DisplayMetrics()
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        val (width, height) = scaledCaptureSize(
            metrics.widthPixels,
            metrics.heightPixels,
            RecorderConfig.LONG_EDGE
        )
        return Triple(width, height, metrics.densityDpi)
    }

    @Suppress("DEPRECATION")
    private fun watchSize(): Triple<Int, Int, Int> {
        val metrics = DisplayMetrics()
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        val (width, height) = scaledCaptureSize(
            metrics.widthPixels,
            metrics.heightPixels,
            RecorderConfig.WATCH_LONG_EDGE
        )
        return Triple(width, height, metrics.densityDpi)
    }

    private fun newRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= 31) newRecorder31() else MediaRecorder()
    }

    @RequiresApi(31)
    private fun newRecorder31(): MediaRecorder = MediaRecorder(this)

    private fun holdWake() {
        val power = getSystemService(PowerManager::class.java)
        val lock = wakeLock ?: power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "tillrecorder:capture"
        ).also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        lock.acquire(RecorderConfig.SEGMENT_MS + 120_000L)
    }

    private fun releaseWake() {
        val lock = wakeLock ?: return
        if (lock.isHeld) lock.release()
    }

    private fun applyCamera(wanted: Boolean) {
        if (!running || stopRequested.get()) return
        if (wanted == cameraOn) {
            if (cameraOn) holdCameraWake()
            return
        }
        if (wanted) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                if (!cameraPermissionNoted) {
                    cameraPermissionNoted = true
                    publish("Front camera was turned on from the website. Open Till Recorder and allow the camera.")
                }
                return
            }
            cameraPermissionNoted = false
            cameraOn = true
            startInForeground("Front camera is on, next to the register screen.")
            holdCameraWake()
            ensureFront().start()
            publish("Front camera is on. The website shows it beside the screen.")
        } else {
            frontCamera?.stop()
            cameraOn = false
            releaseCameraWake()
            startInForeground(
                if (active != null) "Recording this register." else "Watching. A clip starts when the screen changes."
            )
            publish("Front camera is off.")
        }
    }

    private fun ensureFront(): FrontCamera {
        val existing = frontCamera
        if (existing != null) return existing
        val created = FrontCamera(this) { jpeg ->
            val settings = store.current()
            net.execute { ShopClient.uploadCamera(settings, jpeg) }
        }
        frontCamera = created
        return created
    }

    private fun releaseFront() {
        cameraOn = false
        frontCamera?.shutdown()
        frontCamera = null
        releaseCameraWake()
    }

    private fun holdCameraWake() {
        val power = getSystemService(PowerManager::class.java)
        val lock = cameraWake ?: power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "tillrecorder:camera"
        ).also {
            it.setReferenceCounted(false)
            cameraWake = it
        }
        if (!lock.isHeld) lock.acquire(30 * 60 * 1000L)
    }

    private fun releaseCameraWake() {
        val lock = cameraWake ?: return
        if (lock.isHeld) lock.release()
    }

    private fun startInForeground(text: String) {
        var type = 0
        if (Build.VERSION.SDK_INT >= 29) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        if (Build.VERSION.SDK_INT >= 30) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (cameraOn && Build.VERSION.SDK_INT >= 34) {
            type = type or cameraForegroundType()
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification(text), type)
    }

    @RequiresApi(34)
    private fun cameraForegroundType(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(
                when {
                    cameraOn -> "Front camera is on"
                    active != null -> "Recording this register"
                    else -> "Watching this register"
                }
            )
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stat_record, getString(R.string.notif_stop), stop)
            .build()
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
    }

    private data class Active(
        val recorder: MediaRecorder,
        val display: VirtualDisplay,
        val file: File,
        val started: Long,
    )

    companion object {
        private const val TAG = "TillRecorder"
        private const val CHANNEL = "till_recording"
        private const val NOTIF_ID = 7
        private const val ACTION_START = "com.tillrecorder.agent.START"
        private const val ACTION_STOP = "com.tillrecorder.agent.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
