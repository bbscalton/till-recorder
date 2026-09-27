package com.tillrecorder.agent

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Front camera as real H.264, only while the watch page has the camera on.
 * Closing this releases the camera so the indicator goes away.
 */
class CameraVideo(
    private val context: Context,
    private val onMotion: () -> Unit,
    private val onChunk: (sequence: Int, bytes: ByteArray, codec: String) -> Unit,
    private val onFailed: () -> Unit,
) {
    private val thread = HandlerThread("till-camera-video").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var open = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var encoder: SurfaceEncoder? = null
    private var previous: ByteArray? = null
    private var motionReader = true

    fun start() {
        handler.post {
            if (open) return@post
            open = true
            try {
                openCamera()
            } catch (error: Exception) {
                Log.w(TAG, "Front camera did not open", error)
                open = false
                closeAll()
                onFailed()
            }
        }
    }

    fun openFile(file: java.io.File): Boolean = encoder?.openFile(file) == true

    fun closeFile(): Boolean = encoder?.closeFile() == true

    fun shutdown() {
        val done = CountDownLatch(1)
        handler.post {
            open = false
            closeAll()
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull { cameraId ->
            manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        }
        if (id == null) {
            open = false
            onFailed()
            return
        }
        val characteristics = manager.getCameraCharacteristics(id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            open = false
            onFailed()
            return
        }
        val size = pickSize(map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList().orEmpty())
        val rotation = videoRotation(characteristics)
        val created = SurfaceEncoder(size.width, size.height, 1_200_000, rotation, false, onChunk)
        val surface = created.start()
        encoder = created
        Log.i(TAG, "Camera video ${size.width}x${size.height} at ${RecorderConfig.FRAME_RATE} fps")
        val motionSize = pickMotion(map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty())
        val imageReader = if (motionSize != null) {
            ImageReader.newInstance(motionSize.width, motionSize.height, ImageFormat.YUV_420_888, 2)
        } else {
            null
        }
        imageReader?.setOnImageAvailableListener({ source -> onImage(source) }, handler)
        reader = imageReader
        motionReader = imageReader != null
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!open) {
                    camera.close()
                    return
                }
                device = camera
                val targets = listOfNotNull(surface, imageReader?.surface)
                try {
                    camera.createCaptureSession(targets, sessionCallback(camera, surface, targets), handler)
                } catch (error: Exception) {
                    Log.w(TAG, "Front camera session did not start", error)
                    onFailed()
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (device == camera) device = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.w(TAG, "Front camera error $error")
                camera.close()
                if (device == camera) device = null
                onFailed()
            }
        }, handler)
    }

    private fun sessionCallback(
        camera: CameraDevice,
        encoderSurface: Surface,
        targets: List<Surface>,
    ) = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(captureSession: CameraCaptureSession) {
            if (!open) {
                captureSession.close()
                return
            }
            session = captureSession
            try {
                startRepeating(camera, captureSession, encoderSurface, includeMotion = motionReader)
            } catch (error: Exception) {
                Log.w(TAG, "Front camera request failed", error)
                onFailed()
            }
        }

        override fun onConfigureFailed(captureSession: CameraCaptureSession) {
            Log.w(TAG, "Front camera session failed")
            if (!open || device == null) return
            if (targets.size > 1) {
                openEncoderOnly(camera, encoderSurface)
            } else {
                onFailed()
            }
        }
    }

    private fun openEncoderOnly(camera: CameraDevice, encoderSurface: Surface) {
        motionReader = false
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try {
            camera.createCaptureSession(
                listOf(encoderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        if (!open) {
                            captureSession.close()
                            return
                        }
                        session = captureSession
                        try {
                            startRepeating(camera, captureSession, encoderSurface, includeMotion = false)
                        } catch (error: Exception) {
                            Log.w(TAG, "Front camera request failed", error)
                            onFailed()
                        }
                    }

                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        onFailed()
                    }
                },
                handler,
            )
        } catch (error: Exception) {
            onFailed()
        }
    }

    private fun startRepeating(
        camera: CameraDevice,
        captureSession: CameraCaptureSession,
        encoderSurface: Surface,
        includeMotion: Boolean,
    ) {
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        request.addTarget(encoderSurface)
        if (includeMotion) reader?.surface?.let { request.addTarget(it) }
        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        fpsRange()?.let { request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        captureSession.setRepeatingRequest(request.build(), null, handler)
        handler.removeCallbacks(sync)
        handler.post(sync)
        if (!includeMotion) handler.post(keepAlive)
    }

    private val sync = object : Runnable {
        override fun run() {
            if (!open) return
            encoder?.requestSync()
            handler.postDelayed(this, 60)
        }
    }

    private val keepAlive = object : Runnable {
        override fun run() {
            if (!open || motionReader) return
            onMotion()
            handler.postDelayed(this, 1_000)
        }
    }

    private fun fpsRange(): Range<Int>? {
        val id = device?.id ?: return null
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ranges = manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.filter { it.lower >= RecorderConfig.FRAME_RATE }.minByOrNull { it.upper }
            ?: ranges.maxByOrNull { it.upper }
    }

    private fun onImage(source: ImageReader) {
        val image = source.acquireLatestImage() ?: return
        try {
            val luma = ySample(image)
            val prior = previous
            previous = luma
            if (prior != null && prior.size == luma.size &&
                changedFraction(prior, luma) >= RecorderConfig.MOTION_FRACTION
            ) {
                onMotion()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Camera motion sample failed", error)
        } finally {
            image.close()
        }
    }

    private fun closeAll() {
        handler.removeCallbacks(keepAlive)
        handler.removeCallbacks(sync)
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        previous = null
    }

    private fun pickSize(sizes: List<Size>): Size {
        val even = sizes.filter { it.width % 2 == 0 && it.height % 2 == 0 }
        val pool = even.ifEmpty { sizes }
        if (pool.isEmpty()) return Size(640, 480)
        return pool
            .filter { max(it.width, it.height) in 640..1280 }
            .minByOrNull { abs(max(it.width, it.height) - 960) }
            ?: pool.minBy { abs(max(it.width, it.height) - 960) }
    }

    private fun pickMotion(sizes: List<Size>): Size? {
        if (sizes.isEmpty()) return null
        return sizes.filter { it.width <= 640 && it.height <= 480 }.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
    }

    private fun videoRotation(characteristics: CameraCharacteristics): Int {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val rotation = try {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            manager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        }
        var deviceOrientation = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) deviceOrientation = -deviceOrientation
        return (sensor + deviceOrientation + 360) % 360
    }

    private fun ySample(image: Image): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val stride = plane.rowStride.coerceAtLeast(1)
        val step = 16
        val cols = (image.width / step).coerceAtLeast(1)
        val rows = (image.height / step).coerceAtLeast(1)
        val out = ByteArray(cols * rows)
        var i = 0
        var y = 0
        while (y < image.height && i < out.size) {
            var x = 0
            while (x < image.width && i < out.size) {
                val pos = y * stride + x
                out[i++] = if (pos >= 0 && pos < buffer.capacity()) buffer.get(pos) else 0
                x += step
            }
            y += step
        }
        return if (i == out.size) out else out.copyOf(i)
    }

    companion object {
        private const val TAG = "TillRecorder"
    }
}
