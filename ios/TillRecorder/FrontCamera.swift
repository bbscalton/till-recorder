import AVFoundation
import CoreMedia
import Foundation

/// Front camera while this app is open. iOS suspends the app in the background,
/// so the camera stops then. The screen broadcast is a separate process and keeps going.
final class FrontCamera: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    private let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "till.camera")
    private let encoder = H264Encoder()
    private var muxer: Fmp4Muxer?
    private var sequence = 1
    private var batch: [(data: Data, keyframe: Bool)] = []
    private var codec = "avc1.42E01E"
    private var clip = Data()
    private var clipMuxer: Fmp4Muxer?
    private var clipStarted = Date()
    private(set) var running = false
    var status = ""

    func start() {
        guard !running else { return }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configure()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] allowed in
                DispatchQueue.main.async {
                    if allowed { self?.configure() } else { self?.status = "Camera permission is off. The screen broadcast still runs." }
                }
            }
        default:
            status = "Camera permission is off. The screen broadcast still runs."
        }
    }

    func stop() {
        if session.isRunning { session.stopRunning() }
        encoder.stop()
        finishClip()
        running = false
        muxer = nil
        status = ""
    }

    private func configure() {
        session.beginConfiguration()
        session.sessionPreset = .vga640x480
        session.inputs.forEach { session.removeInput($0) }
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input)
        else {
            session.commitConfiguration()
            status = "The front camera did not open. Screen broadcast still runs."
            return
        }
        session.addInput(input)
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        output.setSampleBufferDelegate(self, queue: queue)
        if session.canAddOutput(output) { session.addOutput(output) }
        session.commitConfiguration()
        encoder.onSample = { [weak self] sample in self?.consume(sample) }
        session.startRunning()
        running = session.isRunning
        if !running { status = "The front camera did not open. Screen broadcast still runs." }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixel = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        encoder.encode(pixel, time: CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
    }

    private func consume(_ sample: CMSampleBuffer) {
        guard let sets = H264Encoder.parameterSets(sample),
              let avcc = H264Encoder.avcc(sample),
              let format = CMSampleBufferGetFormatDescription(sample)
        else { return }
        if muxer == nil {
            let size = CMVideoFormatDescriptionGetDimensions(format)
            muxer = Fmp4Muxer(width: Int(size.width), height: Int(size.height), frameRate: 15)
            codec = Fmp4Muxer.codecString(sps: sets.sps)
            sequence = 1
            ShopClient.uploadStream(kind: "camera", sequence: 0, bytes: muxer!.start(sps: sets.sps, pps: sets.pps), codec: codec) { _ in }
            clipMuxer = Fmp4Muxer(width: Int(size.width), height: Int(size.height), frameRate: 15)
            clip = clipMuxer!.start(sps: sets.sps, pps: sets.pps)
            clipStarted = Date()
        }
        let key = H264Encoder.isKeyframe(sample)
        batch.append((avcc, key))
        if batch.count >= 15 {
            let media = muxer!.media(samples: batch)
            batch.removeAll(keepingCapacity: true)
            let seq = sequence
            sequence += 1
            ShopClient.uploadStream(kind: "camera", sequence: seq, bytes: media, codec: codec) { _ in }
        }
        if let clipMuxer {
            clip.append(clipMuxer.media(samples: [(avcc, key)]))
            if Date().timeIntervalSince(clipStarted) > 20 || clip.count > 4_000_000 { finishClip() }
        }
    }

    private func finishClip() {
        guard clip.count > 32 else { return }
        let started = Int(clipStarted.timeIntervalSince1970 * 1000)
        let ended = Int(Date().timeIntervalSince1970 * 1000)
        let url = SessionStore.pendingClips.appendingPathComponent("cam-\(started).mp4")
        let meta = url.deletingPathExtension().appendingPathExtension("json")
        try? clip.write(to: url)
        try? JSONSerialization.data(withJSONObject: ["started": started, "ended": ended, "kind": "camera"]).write(to: meta)
        ShopClient.uploadClip(file: url, kind: "camera", startedAtMs: started, endedAtMs: ended) { ok in
            if ok { try? FileManager.default.removeItem(at: meta) }
            if ok { try? FileManager.default.removeItem(at: url) }
        }
        clip.removeAll()
        clipMuxer = nil
        muxer = nil
    }
}
