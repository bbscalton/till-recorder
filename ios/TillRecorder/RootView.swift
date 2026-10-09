import ReplayKit
import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Till Recorder")
                    .font(.largeTitle.bold())
                Text(model.message)
                    .foregroundStyle(.secondary)
                if model.locked {
                    Text("The watch page locked this register. The screen broadcast keeps running.")
                }
                if model.paired {
                    paired
                } else {
                    pairForm
                }
                limits
            }
            .padding(24)
            .frame(maxWidth: 560, alignment: .leading)
        }
        .onAppear { model.appear() }
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.appear() }
            if phase == .background { model.disappear() }
        }
    }

    private var pairForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Pair with a code from the watch page. You do not type a web address or a token.")
            TextField("Register name", text: $model.name)
                .textFieldStyle(.roundedBorder)
            TextField("Pair code", text: $model.code)
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.characters)
            Button("Pair") { model.pair() }
                .buttonStyle(.borderedProminent)
        }
    }

    private var paired: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Paired as \(SessionStore.deviceName).")
            Text("Start the screen broadcast. It stays on until you stop it.")
            BroadcastPicker()
                .frame(width: 64, height: 64)
            Text("Or open Control Center, press and hold Screen Recording, and choose Till Recorder.")
            Text(model.cameraOn ? "Front camera is on while this app is open." : "Front camera is off until the watch page turns it on.")
                .foregroundStyle(.secondary)
            Text("Notes typed here are saved. iOS cannot read what is typed in other apps.")
                .foregroundStyle(.secondary)
            TextField("Note typed in Till Recorder", text: $model.note)
                .textFieldStyle(.roundedBorder)
            Button("Save note") { model.saveNote() }
        }
    }

    private var limits: some View {
        Text("iOS cannot hide this app the way Android hides its icon, and it cannot keep recording after a restart until you start the broadcast again. The screen broadcast continues in the background. The front camera runs only while Till Recorder is open.")
            .font(.footnote)
            .foregroundStyle(.secondary)
    }
}

struct BroadcastPicker: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 64, height: 64))
        picker.preferredExtension = "com.tillrecorder.ios.broadcast"
        picker.showsMicrophoneButton = false
        return picker
    }

    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {}
}
