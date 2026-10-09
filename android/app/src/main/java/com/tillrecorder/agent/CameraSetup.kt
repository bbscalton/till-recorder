package com.tillrecorder.agent

/**
 * Checks a camera before it is saved: ONVIF addresses are turned into an RTSP stream
 * (H.264 profile first, then the sub stream) and the RTSP stream must answer DESCRIBE
 * with H.264 video. Errors say exactly what failed.
 */
object CameraSetup {
    data class Outcome(val url: String, val error: String?)

    fun check(target: CameraAddress.Target): Outcome {
        return when (target.kind) {
            CameraAddress.Kind.INVALID -> Outcome("", target.error)
            CameraAddress.Kind.RTSP -> {
                val error = RtspClient.probe(target.url, target.user, target.password)
                    ?: return Outcome(target.url, null)
                if (error.kind == CameraException.Kind.CODEC) {
                    // Main stream is H.265 (common default); the sub stream is often H.264.
                    val sub = CameraAddress.subStream(target.url)
                    if (sub != null && RtspClient.probe(sub, target.user, target.password) == null) return Outcome(sub, null)
                }
                Outcome("", error.message)
            }
            CameraAddress.Kind.ONVIF -> {
                val streams = OnvifDiscovery.streams(target.url, target.user, target.password)
                if (streams.error != null) return Outcome("", streams.error.message)
                var first: CameraException? = null
                for (url in streams.urls) {
                    val error = RtspClient.probe(url, target.user, target.password) ?: return Outcome(url, null)
                    if (first == null || (error.kind == CameraException.Kind.CODEC && first.kind != CameraException.Kind.CODEC)) first = error
                    // A rejected login will not get better on the next profile; stop to avoid locking the account.
                    if (error.kind == CameraException.Kind.AUTH || error.kind == CameraException.Kind.UNREACHABLE) break
                }
                Outcome("", first?.message ?: "The camera did not give a playable stream.")
            }
        }
    }
}
