package com.tillrecorder.agent

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.JavaI420Buffer
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Real-time video to the watch page, same WebRTC shape SareChild uses for a camera:
 * the viewer offers, this register answers, and media goes peer-to-peer through STUN.
 *
 * Screen frames are the bitmaps [TillAccessibilityService.takeScreenshot] already produced.
 * That capture rate is the ceiling. Camera frames are the front camera's YUV output from
 * the same Camera2 session that records clips.
 */
class LiveRtc(
    context: Context,
    private val onCameraPreview: (Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("till-rtc").apply { start() }
    private val handler = Handler(thread.looper)
    private val posts = Executors.newSingleThreadExecutor()
    private val peers = HashMap<String, Peer>()
    @Volatile private var screenOpen = false
    @Volatile private var cameraOpen = false
    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var sender: ((kind: String, session: String, answerType: String?, answerSdp: String?, ice: List<RtcIce>?) -> String)? = null

    fun start(post: (kind: String, session: String, answerType: String?, answerSdp: String?, ice: List<RtcIce>?) -> String) {
        sender = post
    }

    fun onBundle(bundle: RtcBundle) {
        handler.post {
            accept(peers.getOrPut("screen") { Peer("screen") }, bundle.screen, screencast = true)
            accept(peers.getOrPut("camera") { Peer("camera") }, bundle.camera, screencast = false)
        }
    }

    fun pushScreen(bitmap: Bitmap) {
        if (!screenOpen) return
        val width = bitmap.width
        val height = bitmap.height
        if (width < 2 || height < 2) return
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        handler.post {
            val peer = peers["screen"] ?: return@post
            sendArgb(peer, width, height, pixels)
        }
    }

    fun pushCamera(width: Int, height: Int, rotation: Int, y: ByteArray, u: ByteArray, v: ByteArray) {
        if (!cameraOpen) return
        handler.post {
            val peer = peers["camera"] ?: return@post
            sendI420(peer, width, height, rotation, y, u, v)
        }
    }

    fun close(kind: String) {
        handler.post {
            peers.remove(kind)?.let { release(it) }
            if (kind == "camera") onCameraPreview(false)
        }
    }

    fun stop() {
        handler.post {
            peers.values.forEach { release(it) }
            peers.clear()
            onCameraPreview(false)
            try { factory?.dispose() } catch (_: Exception) {}
            factory = null
            try { eglBase?.release() } catch (_: Exception) {}
            eglBase = null
        }
        posts.shutdown()
        thread.quitSafely()
    }

    private fun accept(peer: Peer, room: RtcRoom, screencast: Boolean) {
        if (room.session.isBlank() || room.offerType != "offer" || room.offerSdp.length < 20) return
        if (peer.session == room.session && room.updatedAt > 0 &&
            System.currentTimeMillis() - room.updatedAt > 15_000
        ) {
            release(peer)
            peer.session = ""
            return
        }
        if (peer.session == room.session && peer.failed) return
        if (peer.session != room.session || peer.pc == null) {
            release(peer)
            peer.session = room.session
            peer.failed = false
            begin(peer, room, screencast)
            return
        }
        addViewerIce(peer, room.viewerIce)
    }

    private fun begin(peer: Peer, room: RtcRoom, screencast: Boolean) {
        try {
            val pcFactory = ensureFactory()
            val source = pcFactory.createVideoSource(screencast)
            source.capturerObserver.onCapturerStarted(true)
            peer.source = source
            peer.live = true
            if (peer.kind == "camera") {
                cameraOpen = true
                onCameraPreview(true)
            } else {
                screenOpen = true
            }
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            val connection = pcFactory.createPeerConnection(rtcConfig, observer(peer)) ?: run {
                fail(peer, "peer connection was not created")
                return
            }
            peer.pc = connection
            val track = pcFactory.createVideoTrack("till_${peer.kind}", source)
            connection.addTrack(track, listOf("till-${peer.kind}"))
            val offer = SessionDescription(SessionDescription.Type.OFFER, room.offerSdp)
            connection.setRemoteDescription(object : org.webrtc.SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) = Unit
                override fun onSetSuccess() {
                    handler.post {
                        peer.remoteSet = true
                        flushPendingIce(peer)
                        addViewerIce(peer, room.viewerIce)
                        connection.createAnswer(object : org.webrtc.SdpObserver {
                            override fun onCreateSuccess(description: SessionDescription?) {
                                if (description == null) return
                                connection.setLocalDescription(object : org.webrtc.SdpObserver {
                                    override fun onCreateSuccess(ignored: SessionDescription?) = Unit
                                    override fun onSetSuccess() {
                                        handler.post { publish(peer, description.type.canonicalForm(), description.description) }
                                    }
                                    override fun onCreateFailure(error: String?) = Unit
                                    override fun onSetFailure(error: String?) {
                                        handler.post { fail(peer, "local description was rejected") }
                                    }
                                }, description)
                            }
                            override fun onSetSuccess() = Unit
                            override fun onCreateFailure(error: String?) {
                                handler.post { fail(peer, "answer was not created") }
                            }
                            override fun onSetFailure(error: String?) = Unit
                        }, MediaConstraints())
                    }
                }
                override fun onCreateFailure(error: String?) = Unit
                override fun onSetFailure(error: String?) {
                    handler.post { fail(peer, "offer was rejected") }
                }
            }, offer)
            Log.i(TAG, "WebRTC ${peer.kind} answering")
        } catch (error: Exception) {
            Log.w(TAG, "WebRTC ${peer.kind} did not start", error)
            fail(peer, "start failed")
        }
    }

    private fun observer(peer: Peer) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.i(TAG, "WebRTC ${peer.kind} ice $state")
            if (state == PeerConnection.IceConnectionState.CONNECTED ||
                state == PeerConnection.IceConnectionState.COMPLETED
            ) {
                Log.i(TAG, "WebRTC ${peer.kind} connected")
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null) return
            handler.post {
                peer.localIce.add(
                    RtcIce(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                )
                publish(peer, null, null)
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) = Unit
    }

    private fun addViewerIce(peer: Peer, ice: List<RtcIce>) {
        val connection = peer.pc ?: return
        while (peer.applied < ice.size) {
            val item = ice[peer.applied]
            peer.applied += 1
            val candidate = IceCandidate(item.sdpMid ?: "", item.sdpMLineIndex, item.candidate)
            if (peer.remoteSet) connection.addIceCandidate(candidate) else peer.pendingIce.add(candidate)
        }
    }

    private fun flushPendingIce(peer: Peer) {
        val connection = peer.pc ?: return
        for (candidate in peer.pendingIce) connection.addIceCandidate(candidate)
        peer.pendingIce.clear()
    }

    private fun publish(peer: Peer, answerType: String?, answerSdp: String?) {
        if (answerType != null && answerSdp != null) {
            peer.answerType = answerType
            peer.answerSdp = answerSdp
        }
        val session = peer.session
        val type = if (!peer.sentAnswer) peer.answerType else null
        val sdp = if (!peer.sentAnswer) peer.answerSdp else null
        if (type != null) peer.sentAnswer = true
        val ice = peer.localIce.toList()
        val post = sender ?: return
        val includeAnswer = type != null
        posts.execute {
            val result = try {
                post(peer.kind, session, type, sdp, ice)
            } catch (error: Exception) {
                "fail"
            }
            if (result == "ok" || !includeAnswer) return@execute
            handler.post {
                if (peer.session != session) return@post
                peer.sentAnswer = false
                Log.w(TAG, "WebRTC ${peer.kind} answer was not saved ($result)")
                if (result == "stale") {
                    release(peer)
                    peer.session = ""
                    peer.failed = false
                }
            }
        }
    }

    private fun sendArgb(peer: Peer, width: Int, height: Int, pixels: IntArray) {
        val source = peer.source ?: return
        if (width % 2 != 0 || height % 2 != 0) return
        val buffer = JavaI420Buffer.allocate(width, height)
        fillI420(buffer, width, height, pixels)
        val frame = VideoFrame(buffer, 0, System.nanoTime())
        try {
            source.capturerObserver.onFrameCaptured(frame)
            note(peer)
        } finally {
            frame.release()
        }
    }

    private fun sendI420(
        peer: Peer,
        width: Int,
        height: Int,
        rotation: Int,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
    ) {
        val source = peer.source ?: return
        if (width % 2 != 0 || height % 2 != 0) return
        if (y.size < width * height || u.size < (width / 2) * (height / 2)) return
        val buffer = JavaI420Buffer.allocate(width, height)
        putPlane(buffer.dataY, buffer.strideY, y, width, height)
        putPlane(buffer.dataU, buffer.strideU, u, width / 2, height / 2)
        putPlane(buffer.dataV, buffer.strideV, v, width / 2, height / 2)
        val frame = VideoFrame(buffer, rotation, System.nanoTime())
        try {
            source.capturerObserver.onFrameCaptured(frame)
            note(peer)
        } finally {
            frame.release()
        }
    }

    private fun note(peer: Peer) {
        peer.frames += 1
        val now = SystemClock.elapsedRealtime()
        if (peer.fpsAt == 0L) peer.fpsAt = now
        val elapsed = now - peer.fpsAt
        if (elapsed < 5_000) return
        val fps = peer.frames * 1000.0 / elapsed
        val interval = elapsed.toDouble() / peer.frames
        Log.i(TAG, "WebRTC ${peer.kind} %.1f fps, frame interval %.0f ms".format(fps, interval))
        peer.frames = 0
        peer.fpsAt = now
    }

    private fun fail(peer: Peer, reason: String) {
        Log.w(TAG, "WebRTC ${peer.kind} $reason")
        peer.failed = true
        peer.live = false
        if (peer.kind == "camera") {
            cameraOpen = false
            onCameraPreview(false)
        } else {
            screenOpen = false
        }
    }

    private fun release(peer: Peer) {
        if (peer.kind == "camera") cameraOpen = false else screenOpen = false
        peer.live = false
        peer.remoteSet = false
        peer.sentAnswer = false
        peer.applied = 0
        peer.answerType = null
        peer.answerSdp = null
        peer.localIce.clear()
        peer.pendingIce.clear()
        peer.frames = 0
        peer.fpsAt = 0L
        try { peer.pc?.dispose() } catch (_: Exception) {}
        peer.pc = null
        try { peer.source?.dispose() } catch (_: Exception) {}
        peer.source = null
        if (peer.kind == "camera") onCameraPreview(false)
    }

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        synchronized(LiveRtc::class.java) {
            if (!initialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .setEnableInternalTracer(false)
                        .setFieldTrials("WebRTC-HideLocalIpsWithMdns/Disabled/")
                        .createInitializationOptions()
                )
                initialized = true
            }
        }
        val egl = EglBase.create()
        eglBase = egl
        val created = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        factory = created
        return created
    }

    private class Peer(val kind: String) {
        var session = ""
        var pc: PeerConnection? = null
        var source: VideoSource? = null
        var live = false
        var failed = false
        var remoteSet = false
        var sentAnswer = false
        var applied = 0
        var answerType: String? = null
        var answerSdp: String? = null
        val localIce = ArrayList<RtcIce>()
        val pendingIce = ArrayList<IceCandidate>()
        var frames = 0
        var fpsAt = 0L
    }

    companion object {
        private const val TAG = "TillRecorder"
        private var initialized = false

        private fun iceServers(): List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )

        private fun fillI420(buffer: JavaI420Buffer, width: Int, height: Int, argb: IntArray) {
            val y = ByteArray(width * height)
            val u = ByteArray((width / 2) * (height / 2))
            val v = ByteArray((width / 2) * (height / 2))
            var pixel = 0
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val color = argb[pixel]
                    val r = (color shr 16) and 0xff
                    val g = (color shr 8) and 0xff
                    val b = color and 0xff
                    y[pixel] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                    if (row % 2 == 0 && col % 2 == 0) {
                        val chroma = (row / 2) * (width / 2) + (col / 2)
                        u[chroma] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                        v[chroma] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                    }
                    pixel += 1
                }
            }
            putPlane(buffer.dataY, buffer.strideY, y, width, height)
            putPlane(buffer.dataU, buffer.strideU, u, width / 2, height / 2)
            putPlane(buffer.dataV, buffer.strideV, v, width / 2, height / 2)
        }

        private fun putPlane(dst: ByteBuffer, stride: Int, src: ByteArray, width: Int, height: Int) {
            val out = dst.duplicate()
            out.clear()
            var offset = 0
            for (row in 0 until height) {
                out.position(row * stride)
                out.put(src, offset, width)
                offset += width
            }
        }
    }
}
