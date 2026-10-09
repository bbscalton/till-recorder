import CoreMedia
import CoreVideo
import Foundation

/// Turns the ReplayKit screen into live fMP4 and motion clips. The extension
/// process stays up for as long as the owner leaves the broadcast on.
final class ScreenRelay {
    private let queue = DispatchQueue(label: "till.screen")
    private let encoder = H264Encoder()
    private var liveMuxer: Fmp4Muxer?
    private var liveSequence = 1
    private var liveBatch: [(data: Data, keyframe: Bool)] = []
    private var codec = "avc1.42E01E"
    private var sentInit = false
    private var lastMotion = Date.distantPast
    private var previousSample: [UInt8] = []
    private var clipMuxer: Fmp4Muxer?
    private var clipBytes = Data()
    private var clipStarted = Date()
    private var heartbeat: DispatchSourceTimer?
    private var uploading = Set<String>()

    func start() {
        SessionStore.markBroadcast(active: true)
        encoder.onSample = { [weak self] sample in
            self?.queue.async { self?.consume(sample) }
        }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: 10)
        timer.setEventHandler { [weak self] in self?.beat() }
        timer.resume()
        heartbeat = timer
        flushPending()
    }

    func stop() {
        heartbeat?.cancel()
        heartbeat = nil
        encoder.stop()
        finishClip(force: true)
        SessionStore.markBroadcast(active: false)
        ShopClient.heartbeat(recording: false) { _ in }
    }

    func append(_ pixel: CVPixelBuffer, time: CMTime) {
        guard SessionStore.recordingEnabled else { return }
        if pictureChanged(pixel) { lastMotion = Date() }
        encoder.encode(pixel, time: time)
    }

    private func beat() {
        SessionStore.markBroadcast(active: true)
        let allowed = SessionStore.recordingEnabled
        ShopClient.heartbeat(recording: allowed) { [weak self] control in
            self?.queue.async {
                if let control { SessionStore.recordingEnabled = control.record }
                guard let self else { return }
                if !SessionStore.recordingEnabled {
                    self.finishClip(force: true)
                    self.liveMuxer = nil
                    self.sentInit = false
                    return
                }
                self.flushPending()
            }
        }
        if !allowed {
            finishClip(force: true)
            liveMuxer = nil
            sentInit = false
            return
        }
        if clipMuxer != nil, Date().timeIntervalSince(lastMotion) > 20 {
            finishClip(force: false)
        }
    }

    private func consume(_ sample: CMSampleBuffer) {
        guard SessionStore.recordingEnabled else {
            finishClip(force: true)
            liveMuxer = nil
            sentInit = false
            return
        }
        guard let sets = H264Encoder.parameterSets(sample), let avcc = H264Encoder.avcc(sample), !avcc.isEmpty else { return }
        if liveMuxer == nil {
            guard let format = CMSampleBufferGetFormatDescription(sample) else { return }
            let size = CMVideoFormatDescriptionGetDimensions(format)
            liveMuxer = Fmp4Muxer(width: Int(size.width), height: Int(size.height), frameRate: 15)
            codec = Fmp4Muxer.codecString(sps: sets.sps)
            sentInit = false
            liveSequence = 1
        }
        guard let liveMuxer else { return }
        if !sentInit {
            let initBytes = liveMuxer.start(sps: sets.sps, pps: sets.pps)
            sentInit = true
            ShopClient.uploadStream(kind: "screen", sequence: 0, bytes: initBytes, codec: codec) { _ in }
        }
        let key = H264Encoder.isKeyframe(sample)
        liveBatch.append((avcc, key))
        if liveBatch.count >= 8 || key && liveBatch.count > 1 {
            let media = liveMuxer.media(samples: liveBatch)
            liveBatch.removeAll(keepingCapacity: true)
            let sequence = liveSequence
            liveSequence += 1
            ShopClient.uploadStream(kind: "screen", sequence: sequence, bytes: media, codec: codec) { ok in
                if !ok {
                    ShopClient.uploadStream(kind: "screen", sequence: sequence, bytes: media, codec: self.codec) { _ in }
                }
            }
        }
        appendClip(avcc, key: key, sps: sets.sps, pps: sets.pps, sample: sample)
    }

    private func appendClip(_ avcc: Data, key: Bool, sps: Data, pps: Data, sample: CMSampleBuffer) {
        let moving = Date().timeIntervalSince(lastMotion) < 20
        if clipMuxer == nil {
            guard moving, key, let format = CMSampleBufferGetFormatDescription(sample) else { return }
            let dimensions = CMVideoFormatDescriptionGetDimensions(format)
            let muxer = Fmp4Muxer(width: Int(dimensions.width), height: Int(dimensions.height), frameRate: 15)
            clipMuxer = muxer
            clipBytes = muxer.start(sps: sps, pps: pps)
            clipStarted = Date()
        }
        guard let clipMuxer else { return }
        clipBytes.append(clipMuxer.media(samples: [(avcc, key)]))
        let tooLong = Date().timeIntervalSince(clipStarted) > 120
        let still = Date().timeIntervalSince(lastMotion) > 20
        if clipBytes.count > 6_000_000 || tooLong || still { finishClip(force: false) }
    }

    private func finishClip(force: Bool) {
        guard clipMuxer != nil, clipBytes.count > 32 else {
            clipMuxer = nil
            clipBytes.removeAll()
            return
        }
        if !force, Date().timeIntervalSince(lastMotion) <= 20, clipBytes.count < 6_000_000, Date().timeIntervalSince(clipStarted) <= 120 {
            return
        }
        let started = Int(clipStarted.timeIntervalSince1970 * 1000)
        let ended = Int(Date().timeIntervalSince1970 * 1000)
        let url = SessionStore.pendingClips.appendingPathComponent("\(started).mp4")
        let meta = SessionStore.pendingClips.appendingPathComponent("\(started).json")
        try? clipBytes.write(to: url)
        let json = try? JSONSerialization.data(withJSONObject: ["started": started, "ended": ended, "kind": "screen"])
        try? json?.write(to: meta)
        clipMuxer = nil
        clipBytes.removeAll()
        flushPending()
    }

    private func flushPending() {
        let folder = SessionStore.pendingClips
        let files = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
        for file in files where file.pathExtension == "mp4" {
            let name = file.lastPathComponent
            if uploading.contains(name) { continue }
            uploading.insert(name)
            let metaURL = file.deletingPathExtension().appendingPathExtension("json")
            let meta = (try? JSONSerialization.jsonObject(with: Data(contentsOf: metaURL))) as? [String: Any]
            let started = meta?["started"] as? Int ?? 0
            let ended = meta?["ended"] as? Int ?? started
            let kind = (meta?["kind"] as? String) == "camera" ? "camera" : "screen"
            ShopClient.uploadClip(file: file, kind: kind, startedAtMs: started, endedAtMs: ended) { ok in
                self.queue.async {
                    self.uploading.remove(name)
                    if ok {
                        try? FileManager.default.removeItem(at: file)
                        try? FileManager.default.removeItem(at: metaURL)
                    }
                }
            }
        }
    }

    private func pictureChanged(_ pixel: CVPixelBuffer) -> Bool {
        CVPixelBufferLockBaseAddress(pixel, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixel, .readOnly) }
        let width = CVPixelBufferGetWidth(pixel)
        let height = CVPixelBufferGetHeight(pixel)
        let planar = CVPixelBufferGetPlaneCount(pixel) > 0
        let stride = planar ? CVPixelBufferGetBytesPerRowOfPlane(pixel, 0) : CVPixelBufferGetBytesPerRow(pixel)
        let origin = planar ? CVPixelBufferGetBaseAddressOfPlane(pixel, 0) : CVPixelBufferGetBaseAddress(pixel)
        guard let origin else { return true }
        let bytes = origin.assumingMemoryBound(to: UInt8.self)
        let pixelStep = planar ? 1 : 4
        var sample: [UInt8] = []
        sample.reserveCapacity(64)
        let stepX = max(width / 8, 1)
        let stepY = max(height / 8, 1)
        var y = 0
        while y < height {
            var x = 0
            while x < width {
                sample.append(bytes[y * stride + x * pixelStep])
                x += stepX
            }
            y += stepY
        }
        defer { previousSample = sample }
        guard previousSample.count == sample.count else { return true }
        var diff = 0
        for index in sample.indices { diff += abs(Int(sample[index]) - Int(previousSample[index])) }
        return diff / sample.count > 12
    }
}
