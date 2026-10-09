import Foundation

/// Pairing lives in the app group so the broadcast extension can upload
/// without asking for a web address or a token.
enum SessionStore {
    static let worker = "https://till-recorder.neuereatec.workers.dev"
    static let appGroup = "group.com.tillrecorder.ios"

    private static let defaults = UserDefaults(suiteName: appGroup) ?? .standard

    static var deviceId: String {
        if let existing = defaults.string(forKey: "deviceId"), existing.count >= 8 {
            return existing
        }
        let created = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        defaults.set(created, forKey: "deviceId")
        return created
    }

    static var deviceName: String {
        get { defaults.string(forKey: "deviceName") ?? "" }
        set { defaults.set(String(newValue.prefix(80)), forKey: "deviceName") }
    }

    static var token: String {
        get { defaults.string(forKey: "token") ?? "" }
        set { defaults.set(newValue, forKey: "token") }
    }

    static var isPaired: Bool {
        deviceId.count >= 8 && !deviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !token.isEmpty
    }

    static func markBroadcast(active: Bool) {
        defaults.set(active, forKey: "broadcastOn")
        defaults.set(Date().timeIntervalSince1970, forKey: "broadcastSeen")
    }

    static var broadcastIsLive: Bool {
        guard defaults.bool(forKey: "broadcastOn") else { return false }
        let seen = defaults.double(forKey: "broadcastSeen")
        return Date().timeIntervalSince1970 - seen < 30
    }

    /// Missing means recording stays on, matching tills paired before this flag existed.
    static var recordingEnabled: Bool {
        get {
            if defaults.object(forKey: "recordingEnabled") == nil { return true }
            return defaults.bool(forKey: "recordingEnabled")
        }
        set { defaults.set(newValue, forKey: "recordingEnabled") }
    }

    static var container: URL {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)
            ?? FileManager.default.temporaryDirectory
    }

    static var pendingClips: URL {
        let folder = container.appendingPathComponent("pending", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }
}
