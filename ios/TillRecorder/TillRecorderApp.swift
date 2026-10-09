import SwiftUI

@main
struct TillRecorderApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
        }
    }
}

final class AppModel: ObservableObject {
    @Published var paired = SessionStore.isPaired
    @Published var name = SessionStore.deviceName
    @Published var code = ""
    @Published var note = ""
    @Published var message = ""
    @Published var cameraOn = false
    @Published var locked = false
    let camera = FrontCamera()
    private var timer: Timer?

    func appear() {
        paired = SessionStore.isPaired
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            self?.beat()
        }
        beat()
    }

    func disappear() {
        camera.stop()
    }

    func pair() {
        message = "Pairing…"
        ShopClient.pair(name: name, code: code) { [weak self] ok in
            DispatchQueue.main.async {
                guard let self else { return }
                self.paired = ok && SessionStore.isPaired
                self.code = ""
                self.message = ok ? "Paired. Start the screen broadcast next." : "That pair code did not work. Create a new one on the watch page."
            }
        }
    }

    func saveNote() {
        let text = note
        ShopClient.postInputs(text: text) { [weak self] ok in
            DispatchQueue.main.async {
                if ok { self?.note = "" }
                self?.message = ok ? "Saved the note typed in this app." : "The note was not saved."
            }
        }
    }

    private func beat() {
        guard SessionStore.isPaired else { return }
        ShopClient.heartbeat(recording: SessionStore.recordingEnabled && SessionStore.broadcastIsLive) { [weak self] control in
            DispatchQueue.main.async {
                guard let self, let control else { return }
                self.cameraOn = control.camera && control.record
                self.locked = control.locked
                SessionStore.recordingEnabled = control.record
                if control.record && control.camera {
                    self.camera.start()
                } else {
                    self.camera.stop()
                }
                if !self.camera.status.isEmpty { self.message = self.camera.status }
            }
        }
    }
}
