import CoreMedia
import ReplayKit

/// ReplayKit calls this while the owner has the Till Recorder broadcast on.
/// Apple ends the process when they stop the broadcast, and a reboot does not start it again.
class SampleHandler: RPBroadcastSampleHandler {
    private let relay = ScreenRelay()

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        guard SessionStore.isPaired else {
            finishBroadcastWithError(NSError(
                domain: "TillRecorder",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Open Till Recorder and pair this iPhone or iPad first."]
            ))
            return
        }
        relay.start()
    }

    override func broadcastPaused() {}

    override func broadcastResumed() {}

    override func broadcastFinished() {
        relay.stop()
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video, let pixel = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        relay.append(pixel, time: CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
    }
}
