import Foundation

struct RemoteControl {
    var camera = false
    var locked = false
    var record = true
}

/// Same worker routes the Android app uses. Responses that contain the
/// shared token are not logged.
enum ShopClient {
    static func pair(name: String, code: String, completion: @escaping (Bool) -> Void) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let compact = code.uppercased().filter { $0.isLetter || $0.isNumber }
        guard !trimmed.isEmpty, compact.count == 6 else {
            completion(false)
            return
        }
        let body: [String: String] = [
            "device_id": SessionStore.deviceId,
            "device_name": String(trimmed.prefix(80)),
            "code": compact,
        ]
        send(path: "/api/pair", method: "POST", json: body, authorized: false) { result in
            guard case let .success(data) = result,
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let token = object["token"] as? String,
                  !token.isEmpty
            else {
                completion(false)
                return
            }
            SessionStore.deviceName = trimmed
            SessionStore.token = token
            completion(true)
        }
    }

    static func heartbeat(recording: Bool, completion: @escaping (RemoteControl?) -> Void) {
        guard SessionStore.isPaired else {
            completion(nil)
            return
        }
        let body: [String: Any] = [
            "device_id": SessionStore.deviceId,
            "device_name": SessionStore.deviceName,
            "recording": recording,
        ]
        send(path: "/api/heartbeat", method: "POST", json: body, authorized: true) { result in
            guard case let .success(data) = result,
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            else {
                completion(nil)
                return
            }
            completion(RemoteControl(
                camera: object["camera"] as? Bool ?? false,
                locked: object["locked"] as? Bool ?? false,
                record: object["record"] as? Bool ?? true
            ))
        }
    }

    static func postInputs(text: String, completion: @escaping (Bool) -> Void) {
        let clean = text.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        guard SessionStore.isPaired, !clean.isEmpty else {
            completion(false)
            return
        }
        let day = dayStamp(Date())
        let body: [String: Any] = [
            "device_id": SessionStore.deviceId,
            "device_name": SessionStore.deviceName,
            "local_day": day,
            "entries": [["at": Int(Date().timeIntervalSince1970 * 1000), "text": String(clean.prefix(200))]],
        ]
        send(path: "/api/inputs", method: "POST", json: body, authorized: true) { result in
            if case .success = result { completion(true) } else { completion(false) }
        }
    }

    static func uploadStream(kind: String, sequence: Int, bytes: Data, codec: String, completion: @escaping (Bool) -> Void) {
        guard SessionStore.isPaired, !bytes.isEmpty, bytes.count <= 4_000_000 else {
            completion(false)
            return
        }
        var request = authorizedRequest(path: "/api/stream")
        request.httpMethod = "POST"
        request.setValue("video/mp4", forHTTPHeaderField: "Content-Type")
        request.setValue(SessionStore.deviceId, forHTTPHeaderField: "X-Device-Id")
        request.setValue(kind, forHTTPHeaderField: "X-Stream-Kind")
        request.setValue(String(sequence), forHTTPHeaderField: "X-Stream-Seq")
        request.setValue(String(codec.prefix(32)), forHTTPHeaderField: "X-Stream-Codec")
        request.httpBody = bytes
        data(request) { result in
            if case .success = result { completion(true) } else { completion(false) }
        }
    }

    static func uploadClip(file: URL, kind: String, startedAtMs: Int, endedAtMs: Int, completion: @escaping (Bool) -> Void) {
        guard SessionStore.isPaired, let payload = try? Data(contentsOf: file), payload.count > 32 else {
            completion(false)
            return
        }
        let boundary = "----till\(UUID().uuidString.replacingOccurrences(of: "-", with: ""))"
        var body = Data()
        func field(_ name: String, _ value: String) {
            body.append(ascii("--\(boundary)\r\n"))
            body.append(ascii("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n"))
            body.append(Data(value.utf8))
            body.append(ascii("\r\n"))
        }
        field("device_id", SessionStore.deviceId)
        field("device_name", String(SessionStore.deviceName.prefix(80)))
        field("started_at_ms", String(startedAtMs))
        field("ended_at_ms", String(endedAtMs))
        field("local_day", dayStamp(Date(timeIntervalSince1970: Double(startedAtMs) / 1000)))
        field("kind", kind == "camera" ? "camera" : "screen")
        body.append(ascii("--\(boundary)\r\n"))
        body.append(ascii("Content-Disposition: form-data; name=\"file\"; filename=\"segment.mp4\"\r\n"))
        body.append(ascii("Content-Type: video/mp4\r\n\r\n"))
        body.append(payload)
        body.append(ascii("\r\n--\(boundary)--\r\n"))
        var request = authorizedRequest(path: "/api/segments")
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        data(request) { result in
            if case .success = result { completion(true) } else { completion(false) }
        }
    }

    private static func authorizedRequest(path: String) -> URLRequest {
        var request = URLRequest(url: URL(string: SessionStore.worker + path)!)
        request.timeoutInterval = 30
        if !SessionStore.token.isEmpty {
            request.setValue("Bearer \(SessionStore.token)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    private static func send(path: String, method: String, json: Any, authorized: Bool, completion: @escaping (Result<Data, Error>) -> Void) {
        var request = URLRequest(url: URL(string: SessionStore.worker + path)!)
        request.httpMethod = method
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if authorized, !SessionStore.token.isEmpty {
            request.setValue("Bearer \(SessionStore.token)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try? JSONSerialization.data(withJSONObject: json)
        data(request, completion: completion)
    }

    private static func data(_ request: URLRequest, completion: @escaping (Result<Data, Error>) -> Void) {
        URLSession.shared.dataTask(with: request) { body, response, error in
            if let error {
                completion(.failure(error))
                return
            }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard (200...299).contains(status) else {
                completion(.failure(URLError(.badServerResponse)))
                return
            }
            completion(.success(body ?? Data()))
        }.resume()
    }

    private static func dayStamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }
}

private extension Data {
    mutating func append(ascii text: String) {
        append(Data(text.utf8))
    }
}
