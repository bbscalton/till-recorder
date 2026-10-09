import CoreMedia
import CoreVideo
import Foundation
import VideoToolbox

/// Hardware H.264. Output sample buffers are length-prefixed NALs.
final class H264Encoder {
    var onSample: ((CMSampleBuffer) -> Void)?
    private var session: VTCompressionSession?
    private var width = 0
    private var height = 0

    func encode(_ pixel: CVPixelBuffer, time: CMTime) {
        let w = CVPixelBufferGetWidth(pixel)
        let h = CVPixelBufferGetHeight(pixel)
        if session == nil || w != width || h != height {
            stop()
            width = w
            height = h
            session = makeSession(width: Int32(w), height: Int32(h))
        }
        guard let session else { return }
        var flags: VTEncodeInfoFlags = []
        VTCompressionSessionEncodeFrame(session, imageBuffer: pixel, presentationTimeStamp: time, duration: .invalid, frameProperties: nil, sourceFrameRefcon: nil, infoFlagsOut: &flags)
    }

    func stop() {
        if let session {
            VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
            VTCompressionSessionInvalidate(session)
        }
        session = nil
    }

    private func makeSession(width: Int32, height: Int32) -> VTCompressionSession? {
        var created: VTCompressionSession?
        let status = VTCompressionSessionCreate(allocator: nil, width: width, height: height, codecType: kCMVideoCodecType_H264, encoderSpecification: nil, imageBufferAttributes: nil, compressedDataAllocator: nil, outputCallback: { refcon, _, status, _, sample in
            guard status == noErr, let sample, let refcon else { return }
            let encoder = Unmanaged<H264Encoder>.fromOpaque(refcon).takeUnretainedValue()
            encoder.onSample?(sample)
        }, refcon: Unmanaged.passUnretained(self).toOpaque(), compressionSessionOut: &created)
        guard status == noErr, let created else { return nil }
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Baseline_AutoLevel)
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: NSNumber(value: 30))
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, value: NSNumber(value: 2))
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_AverageBitRate, value: NSNumber(value: 1_200_000))
        VTCompressionSessionPrepareToEncodeFrames(created)
        return created
    }

    static func parameterSets(_ sample: CMSampleBuffer) -> (sps: Data, pps: Data)? {
        guard let format = CMSampleBufferGetFormatDescription(sample) else { return nil }
        var spsPointer: UnsafePointer<UInt8>?
        var spsSize = 0
        var ppsPointer: UnsafePointer<UInt8>?
        var ppsSize = 0
        var count = 0
        guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: 0, parameterSetPointerOut: &spsPointer, parameterSetSizeOut: &spsSize, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil) == noErr,
              CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: 1, parameterSetPointerOut: &ppsPointer, parameterSetSizeOut: &ppsSize, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil) == noErr,
              let spsPointer, let ppsPointer
        else { return nil }
        return (Data(bytes: spsPointer, count: spsSize), Data(bytes: ppsPointer, count: ppsSize))
    }

    static func avcc(_ sample: CMSampleBuffer) -> Data? {
        guard let data = CMSampleBufferGetDataBuffer(sample) else { return nil }
        var length = 0
        var pointer: UnsafeMutablePointer<Int8>?
        guard CMBlockBufferGetDataPointer(data, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &length, dataPointerOut: &pointer) == noErr,
              let pointer, length > 0
        else { return nil }
        return Data(bytes: pointer, count: length)
    }

    static func isKeyframe(_ sample: CMSampleBuffer) -> Bool {
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]],
              let first = attachments.first
        else { return true }
        return !(first[kCMSampleAttachmentKey_NotSync] as? Bool ?? false)
    }
}
