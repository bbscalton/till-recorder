package com.tillrecorder.agent

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
    private var running = false
    private val stopRequested = AtomicBoolean(false)
    private var active: Active? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val watchThread = HandlerThread("till-watch")
    private var watchHandler: Handler? = null
    private var reader: ImageReader? = null
    private var previousLuma: ByteArray? = null
    private var previousCameraLuma: ByteArray? = null
    private var lastScreenMotionAt = 0L
    private var lastCameraMotionAt = 0L
    private var lastCameraLiveAt = 0L
    private var cameraFrameWidth = 0
    private var cameraFrameHeight = 0
    @Volatile private var clipKind = "screen"
    @Volatile private var lastMotionAt = 0L
    private var lastSampleAt = 0L
    private var lastLiveAt = 0L
    private var segmentBusy = false
    private var frontCamera: FrontCamera? = null
    private var screenEncoder: SurfaceEncoder? = null
    private var cameraVideo: CameraVideo? = null
    private var screenClip: Active? = null
    private var cameraClip: Active? = null
    private var screenFrames = 0
    private var screenFpsAt = 0L
    private var cameraOn = false
    private var cameraPermissionNoted = false
    private var cameraWake: PowerManager.WakeLock? = null
    private var builtIn = false
    private var audioSession = false
    private var grabWarned = false
    private var frameWidth = 0
    private var frameHeight = 0
    @Volatile private var activeWriter: ClipWriter? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> if (RegisterLock.isEngaged()) {
                    RegisterLockActivity.show(this@RecordingService)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    lastMotionAt = 0L
                    lastScreenMotionAt = 0L
                    previousLuma = null
                    handler.post {
                        if (active != null) endSegment(continueRecording = false)
                        endEncoder("screen")
                        publish("Screen is off. Waiting until the register is used.")
                    }
                }
            }
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
            val capturing = active != null || screenClip != null || cameraClip != null
            net.execute {
                val control = ShopClient.heartbeat(settings, capturing)
                if (control != null) {
                    if (RegisterLock.shouldPushClear()) {
                        ShopClient.setLocked(settings, locked = false, lockSeq = RegisterLock.pendingClearSeq())
                    }
                    handler.post {
                        applyCamera(control.camera)
                        applyRemoteLock(control)
                    }
                }
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
                notify(notification())
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
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, screenFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, screenFilter)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.deleteNotificationChannel(LEGACY_CHANNEL)
            val channel = NotificationChannel(
                CHANNEL,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setSound(null, null)
            channel.enableVibration(false)
            channel.enableLights(false)
            channel.setShowBadge(false)
            channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> if (intent.getBooleanExtra(EXTRA_AUTHORIZED, false)) {
                requestStop("Watching stopped.")
            } else if (!running && store.watchEnabled) {
                startBuiltIn()
            } else if (!running) {
                stopSelf()
            }
            ACTION_START_BUILTIN -> startBuiltIn()
            else -> if (!running && store.watchEnabled) startBuiltIn() else if (!running) stopSelf()
        }
        return if (store.watchEnabled) START_STICKY else START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (shouldRestart()) scheduleRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val restart = shouldRestart()
        handler.removeCallbacksAndMessages(null)
        running = false
        endSegment(continueRecording = false)
        releaseFront()
        releaseWatch()
        releaseWake()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        watchThread.quitSafely()
        net.shutdownNow()
        if (restart) scheduleRestart()
        super.onDestroy()
    }

    private fun shouldRestart(): Boolean {
        if (!::store.isInitialized) return false
        return running && builtIn && store.watchEnabled && !stopRequested.get()
    }

    private fun startBuiltIn() {
        cancelRestart()
        if (!running) {
            builtIn = true
            store.watchEnabled = true
            handler.removeCallbacks(flush)
            stopRequested.set(false)
            startInForeground("Watching. A clip starts when the screen changes.")
            running = true
            publish("Watching. A clip starts when the screen changes.")
            startScreenEncoder()
            handler.post(grab)
            handler.post(command)
            handler.postDelayed(beat, 60_000)
        } else if (screenEncoder == null) {
            startScreenEncoder()
            handler.post(grab)
        }
    }

    private val grab = object : Runnable {
        override fun run() {
            val again = this
            if (!running || stopRequested.get()) return
            val service = TillAccessibilityService.instance
            if (service == null) {
                if (!grabWarned) {
                    grabWarned = true
                    publish("Turn on Till Recorder in Accessibility. You only do this once.")
                }
                handler.postDelayed(this, 1_000)
                return
            }
            if (grabWarned) {
                grabWarned = false
                publish("Watching. A clip starts when the screen changes.")
            }
            service.capture { bitmap, errorCode ->
                if (!running || stopRequested.get()) {
                    bitmap?.recycle()
                    return@capture
                }
                if (bitmap == null) {
                    val delay = if (errorCode == 3) 40L else 200L
                    handler.postDelayed(again, delay)
                    return@capture
                }
                handler.post(again)
                watchHandler?.post {
                    try {
                        handleFrame(bitmap)
                    } catch (error: Exception) {
                        Log.w(TAG, "Screen sample failed", error)
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun handleFrame(source: android.graphics.Bitmap) {
        val (width, height) = if (frameWidth > 0 && frameHeight > 0) {
            frameWidth to frameHeight
        } else {
            scaledCaptureSize(source.width, source.height, RecorderConfig.LONG_EDGE)
        }
        val bitmap = if (source.width == width && source.height == height) {
            source
        } else {
            android.graphics.Bitmap.createScaledBitmap(source, width, height, true)
        }
        try {
            if (frameWidth == 0) {
                frameWidth = bitmap.width
                frameHeight = bitmap.height
            }
            val now = System.currentTimeMillis()
            lastSampleAt = now
            screenEncoder?.draw(bitmap)
            noteScreenRate(now)
            val luma = sampleLuma(bitmap)
            val previous = previousLuma
            previousLuma = luma
            val moved = previous != null && changedFraction(previous, luma) >= RecorderConfig.MOTION_FRACTION
            if (moved) {
                lastScreenMotionAt = now
                lastMotionAt = now
            }
            handler.post { considerClips() }
        } finally {
            if (bitmap !== source && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun startScreenEncoder() {
        if (screenEncoder != null) return
        val (width, height, _) = captureSize()
        frameWidth = width
        frameHeight = height
        audioSession = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        startInForeground("Watching. A clip starts when the screen changes.")
        try {
            val encoder = SurfaceEncoder(width, height, RecorderConfig.VIDEO_BITRATE, 0, audioSession) { seq, bytes, codec ->
                val settings = store.current()
                net.execute { ShopClient.uploadStream(settings, "screen", seq, bytes, codec) }
            }
            encoder.start()
            screenEncoder = encoder
            Log.i(TAG, "Screen video ${width}x${height} target ${RecorderConfig.FRAME_RATE} fps")
        } catch (error: Exception) {
            Log.e(TAG, "Screen video did not start", error)
            try { screenEncoder?.release() } catch (_: Exception) {}
            screenEncoder = null
            publish("Could not start screen video. ${error.message ?: ""}".trim())
        }
    }

    private fun releaseScreenEncoder() {
        try { screenEncoder?.release() } catch (_: Exception) {}
        screenEncoder = null
    }

    private fun noteScreenRate(now: Long) {
        if (screenFpsAt == 0L) screenFpsAt = now
        screenFrames += 1
        val elapsed = now - screenFpsAt
        if (elapsed < 5_000) return
        Log.i(TAG, "Screen capture %.1f fps".format(screenFrames * 1000.0 / elapsed))
        screenFrames = 0
        screenFpsAt = now
    }

    private fun considerClips() {
        if (!running || stopRequested.get() || segmentBusy) return
        val now = System.currentTimeMillis()
        val screenHot = screenEncoder != null && now - lastScreenMotionAt < RecorderConfig.QUIET_MS
        val cameraHot = cameraOn && cameraVideo != null && now - lastCameraMotionAt < RecorderConfig.QUIET_MS
        segmentBusy = true
        try {
            if (screenHot) {
                if (screenClip == null) openClip("screen")
            } else if (screenClip != null && now - (screenClip?.started ?: now) >= 8_000L) {
                endEncoder("screen")
            }
            if (cameraHot) {
                if (cameraClip == null) openClip("camera")
            } else if (cameraClip != null && now - (cameraClip?.started ?: now) >= 8_000L) {
                endEncoder("camera")
            }
            rotateClip("screen", screenHot, now)
            rotateClip("camera", cameraHot, now)
        } finally {
            segmentBusy = false
        }
    }

    private fun rotateClip(kind: String, hot: Boolean, now: Long) {
        val clip = if (kind == "camera") cameraClip else screenClip
        if (clip != null && now - clip.started >= RecorderConfig.SEGMENT_MS) {
            endEncoder(kind)
            if (hot) openClip(kind)
        }
    }

    private fun openClip(kind: String) {
        val started = System.currentTimeMillis()
        val file = File(RecordingFiles.pendingDir(this), "partial-$kind-$started.mp4")
        val opened = if (kind == "camera") cameraVideo?.openFile(file) == true else screenEncoder?.openFile(file) == true
        if (!opened) {
            file.delete()
            return
        }
        val finish: () -> Boolean = if (kind == "camera") {
            { cameraVideo?.closeFile() == true }
        } else {
            { screenEncoder?.closeFile() == true }
        }
        val clip = Active(null, null, null, file, started, kind, finish)
        if (kind == "camera") cameraClip = clip else screenClip = clip
        holdWake()
        val text = if (kind == "camera") "Recording the front camera." else "Recording ${store.deviceName}"
        startInForeground(text)
        publish(text)
    }

    private fun endEncoder(kind: String) {
        val current = (if (kind == "camera") cameraClip else screenClip) ?: return
        if (kind == "camera") cameraClip = null else screenClip = null
        val saved = try {
            current.finishStream?.invoke() == true
        } catch (error: Exception) {
            Log.w(TAG, "Clip close failed", error)
            false
        }
        val ended = System.currentTimeMillis()
        if (saved && ended > current.started && current.file.exists() && current.file.length() > 1024) {
            val folder = current.file.parentFile
            val finalFile = File(folder, "${current.started}-$ended.mp4")
            if (current.file.renameTo(finalFile)) {
                File(folder, "${current.started}-$ended.json").writeText(
                    JSONObject()
                        .put("startedAtMs", current.started)
                        .put("endedAtMs", ended)
                        .put("kind", current.kind)
                        .toString()
                )
                UploadWorker.enqueue(this)
            }
        } else if (current.file.exists()) {
            current.file.delete()
        }
        if (running && screenClip == null && cameraClip == null && active == null) {
            startInForeground("Watching. A clip starts when the screen changes.")
            publish("Watching. A clip starts when the screen changes.")
        }
    }

    private fun releaseWatch() {
        handler.removeCallbacks(grab)
        endEncoder("screen")
        releaseScreenEncoder()
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        previousLuma = null
    }

    private fun beginSegment() {
        if (!running || stopRequested.get()) return
        openClip("screen")
    }

    private fun beginBuiltInSegment() {
        if (!running || stopRequested.get() || active != null) return
        val now = System.currentTimeMillis()
        val kind = recordingKind(cameraOn, lastCameraMotionAt, lastScreenMotionAt, now, RecorderConfig.QUIET_MS)
        val width: Int
        val height: Int
        if (kind == "camera") {
            width = evenDimension(cameraFrameWidth)
            height = evenDimension(cameraFrameHeight)
            if (width < 2 || height < 2) return
        } else {
            if (frameWidth <= 0 || frameHeight <= 0) return
            width = evenDimension(frameWidth)
            height = evenDimension(frameHeight)
            if (width < 2 || height < 2) return
        }
        val dir = RecordingFiles.pendingDir(this)
        RecordingFiles.trim(dir, RecorderConfig.MAX_PENDING_BYTES)
        dir.listFiles { file -> file.name.startsWith("partial-") }?.forEach { it.delete() }
        val started = System.currentTimeMillis()
        val file = File(dir, "partial-$started.mp4")
        clipKind = kind
        val writer = try {
            ClipWriter(file, width, height).also { it.start() }
        } catch (error: Exception) {
            Log.e(TAG, "Segment failed", error)
            clipKind = "screen"
            file.delete()
            startInForeground("Watching. A clip starts when the screen changes.")
            publish("Could not record. ${error.message ?: ""}".trim())
            return
        }
        audioSession = writer.hasAudio
        startInForeground(if (kind == "camera") "Recording the front camera." else "Recording ${store.deviceName}")
        activeWriter = writer
        active = Active(null, null, writer, file, started, kind)
        holdWake()
        handler.removeCallbacks(rotate)
        handler.postDelayed(rotate, RecorderConfig.SEGMENT_MS)
        publish(if (kind == "camera") "Recording the front camera." else "Recording ${store.deviceName}")
        UploadWorker.enqueue(this)
    }

    private fun endSegment(continueRecording: Boolean) {
        handler.removeCallbacks(rotate)
        val current = active ?: run {
            if (continueRecording && running && !stopRequested.get()) beginSegment()
            return
        }
        active = null
        activeWriter = null
        clipKind = "screen"
        var ended = System.currentTimeMillis()
        var saved = false
        val writer = current.writer
        if (writer != null) {
            saved = try {
                writer.finish()
            } catch (error: Exception) {
                Log.w(TAG, "Short segment discarded", error)
                false
            }
            audioSession = false
            if (running) startInForeground("Watching. A clip starts when the screen changes.")
            if (!saved) {
                current.file.delete()
                ended = -1
            }
        } else try {
            current.recorder?.stop()
            saved = true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Short segment discarded", error)
            current.file.delete()
            ended = -1
        }
        try { current.display?.release() } catch (_: Exception) {}
        try { current.recorder?.release() } catch (_: Exception) {}
        if (saved && ended > current.started && current.file.exists() && current.file.length() > 1024) {
            val folder = current.file.parentFile
            val finalFile = File(folder, "${current.started}-$ended.mp4")
            if (current.file.renameTo(finalFile)) {
                File(folder, "${current.started}-$ended.json").writeText(
                    JSONObject()
                        .put("startedAtMs", current.started)
                        .put("endedAtMs", ended)
                        .put("kind", current.kind)
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
        cancelRestart()
        store.watchEnabled = false
        builtIn = false
        running = false
        handler.removeCallbacks(beat)
        handler.removeCallbacks(command)
        handler.removeCallbacks(grab)
        handler.post {
            endSegment(continueRecording = false)
            releaseFront()
            releaseWatch()
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
        releaseWake()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        if (running && !stopRequested.get()) notify(notification())
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

    private fun applyRemoteLock(control: RemoteControl) {
        val wasEngaged = RegisterLock.isEngaged()
        RegisterLock.apply(control.locked, control.lockSeq)
        if (RegisterLock.isEngaged()) {
            RegisterLockActivity.show(this)
        } else if (wasEngaged) {
            RegisterLockActivity.hide()
        }
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
            val video = CameraVideo(
                this,
                {
                    handler.post {
                        lastCameraMotionAt = System.currentTimeMillis()
                        lastMotionAt = lastCameraMotionAt
                        considerClips()
                    }
                },
                { seq, bytes, codec ->
                    val settings = store.current()
                    net.execute { ShopClient.uploadStream(settings, "camera", seq, bytes, codec) }
                },
                {
                    handler.post {
                        if (!cameraOn) return@post
                        publish("Front camera did not open. It will try again.")
                    }
                },
            )
            cameraVideo = video
            video.start()
            publish("Front camera is on. The website shows it beside the screen.")
        } else {
            endEncoder("camera")
            if (clipKind == "camera" && active != null) {
                endSegment(continueRecording = false)
            }
            cameraVideo?.shutdown()
            cameraVideo = null
            frontCamera?.stop()
            cameraOn = false
            previousCameraLuma = null
            lastCameraMotionAt = 0L
            releaseCameraWake()
            startInForeground(
                if (active != null || screenClip != null) "Recording this register." else "Watching. A clip starts when the screen changes."
            )
            publish("Front camera is off.")
        }
    }

    private fun onCameraJpeg(jpeg: ByteArray) {
        if (!cameraOn || !running || stopRequested.get()) return
        val now = System.currentTimeMillis()
        if (now - lastCameraLiveAt >= RecorderConfig.LIVE_INTERVAL_MS) {
            lastCameraLiveAt = now
            val settings = store.current()
            net.execute { ShopClient.uploadCamera(settings, jpeg) }
        }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        try {
            val luma = sampleLuma(bitmap)
            val previous = previousCameraLuma
            previousCameraLuma = luma
            if (previous != null && changedFraction(previous, luma) >= RecorderConfig.MOTION_FRACTION) {
                lastCameraMotionAt = now
                lastMotionAt = now
            }
            val width = evenDimension(bitmap.width)
            val height = evenDimension(bitmap.height)
            if (width >= 2 && height >= 2) {
                cameraFrameWidth = width
                cameraFrameHeight = height
            }
            if (clipKind == "camera") {
                val frame = if (bitmap.width == width && bitmap.height == height) {
                    bitmap
                } else if (width >= 2 && height >= 2) {
                    android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true)
                } else {
                    null
                }
                if (frame != null) {
                    try {
                        activeWriter?.draw(frame)
                    } finally {
                        if (frame !== bitmap && !frame.isRecycled) frame.recycle()
                    }
                }
            }
            handler.post { considerClips() }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun ensureFront(): FrontCamera {
        val existing = frontCamera
        if (existing != null) return existing
        val created = FrontCamera(
            this,
            { jpeg -> onCameraJpeg(jpeg) },
            {
                handler.post {
                    if (!cameraOn) return@post
                    cameraOn = false
                    publish("Front camera did not open. It will try again.")
                }
            },
        )
        frontCamera = created
        return created
    }

    private fun releaseFront() {
        endEncoder("camera")
        cameraOn = false
        previousCameraLuma = null
        lastCameraMotionAt = 0L
        cameraVideo?.shutdown()
        cameraVideo = null
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
        if (Build.VERSION.SDK_INT >= 34) {
            type = type or specialUseType()
        }
        if (audioSession && Build.VERSION.SDK_INT >= 30) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (cameraOn && Build.VERSION.SDK_INT >= 34) {
            type = type or cameraForegroundType()
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
    }

    @RequiresApi(34)
    private fun specialUseType(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

    @RequiresApi(34)
    private fun cameraForegroundType(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(getString(R.string.notif_quiet_title))
            .setContentText(getString(R.string.notif_quiet_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun restartPending(): PendingIntent {
        return PendingIntent.getForegroundService(
            this,
            3,
            Intent(this, RecordingService::class.java).setAction(ACTION_START_BUILTIN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleRestart() {
        val alarm = getSystemService(AlarmManager::class.java) ?: return
        try {
            alarm.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1_500L,
                restartPending()
            )
        } catch (error: Exception) {
            Log.w(TAG, "Could not schedule watch restart", error)
        }
    }

    private fun cancelRestart() {
        try {
            getSystemService(AlarmManager::class.java)?.cancel(restartPending())
        } catch (_: Exception) {
        }
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
    }

    private data class Active(
        val recorder: MediaRecorder?,
        val display: VirtualDisplay?,
        val writer: ClipWriter?,
        val file: File,
        val started: Long,
        val kind: String,
        val finishStream: (() -> Boolean)? = null,
    )

    companion object {
        private const val TAG = "TillRecorder"
        private const val CHANNEL = "till_status"
        private const val LEGACY_CHANNEL = "till_recording"
        private const val NOTIF_ID = 7
        private const val ACTION_START_BUILTIN = "com.tillrecorder.agent.START_BUILTIN"
        private const val ACTION_STOP = "com.tillrecorder.agent.STOP"
        private const val EXTRA_AUTHORIZED = "authorized_stop"

        fun startBuiltIn(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_START_BUILTIN)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_AUTHORIZED, true)
            )
        }
    }
}
