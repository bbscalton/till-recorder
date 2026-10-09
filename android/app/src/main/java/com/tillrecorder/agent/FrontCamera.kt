package com.tillrecorder.agent

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Front-camera stills for the watch page. Closed completely when the remote switch is off,
 * so the tablet's camera indicator goes away.
 */
class FrontCamera(
    private val context: Context,
    private val onJpeg: (ByteArray) -> Unit,
    private val onFailed: () -> Unit = {},
    private val useBack: () -> Boolean = { false },
) {
    private val thread = HandlerThread("till-front-camera").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var open = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var jpegOrientation = 0

    val isOpen: Boolean get() = open

    private val shoot = object : Runnable {
        override fun run() {
            if (!open) return
            val current = session
            val imageReader = reader
            val camera = device
            if (current == null || imageReader == null || camera == null) {
                handler.postDelayed(this, 500)
                return
            }
            try {
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(imageReader.surface)
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                current.capture(request.build(), null, handler)
            } catch (error: Exception) {
                Log.w(TAG, "Front camera shot failed", error)
            }
            handler.postDelayed(this, 800)
        }
    }

    fun start() {
        handler.post {
            if (open) return@post
            open = true
            try {
                openCamera()
            } catch (error: Exception) {
                Log.w(TAG, "Front camera did not open", error)
                open = false
                closeCamera()
                onFailed()
            }
        }
    }

    fun stop() {
        val done = CountDownLatch(1)
        handler.post {
            open = false
            handler.removeCallbacks(shoot)
            closeCamera()
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
    }

    fun shutdown() {
        stop()
        thread.quitSafely()
    }

    private fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = manager.cameraIdList.toList()
        val id = CameraFacing.pick(ids.map { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }, useBack())
            ?.let { ids[it] }
        if (id == null) {
            open = false
            onFailed()
            return
        }
        val characteristics = manager.getCameraCharacteristics(id)
        jpegOrientation = jpegOrientation(characteristics)
        val size = pickSize(characteristics)
        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        imageReader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                if (bytes.size in 1_000..1_500_000) onJpeg(bytes)
            } finally {
                image.close()
            }
        }, handler)
        reader = imageReader
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!open) {
                    camera.close()
                    return
                }
                device = camera
                try {
                    camera.createCaptureSession(
                        listOf(imageReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(captureSession: CameraCaptureSession) {
                                if (!open) {
                                    captureSession.close()
                                    return
                                }
                                session = captureSession
                                handler.post(shoot)
                            }

                            override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                                Log.w(TAG, "Front camera session failed")
                            }
                        },
                        handler,
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "Front camera session did not start", error)
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
            }
        }, handler)
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
    }

    private fun pickSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(640, 480)
        return sizes
            .filter { max(it.width, it.height) <= 1280 }
            .minByOrNull { abs(max(it.width, it.height) - 640) }
            ?: sizes.minBy { it.width.toLong() * it.height }
    }

    private fun jpegOrientation(characteristics: CameraCharacteristics): Int {
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

    companion object {
        private const val TAG = "TillRecorder"
    }
}
