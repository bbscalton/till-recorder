import Foundation

/// Fragmented MP4 the watch page already plays: init is ftyp+moov, each
/// live piece is moof+mdat. Timescale is 90000.
final class Fmp4Muxer {
    let width: Int
    let height: Int
    let ticksPerFrame: Int
    private var sequence = 1
    private var decodeTime: UInt64 = 0

    init(width: Int, height: Int, frameRate: Int) {
        self.width = max(2, width)
        self.height = max(2, height)
        ticksPerFrame = 90_000 / max(frameRate, 1)
    }

    func start(sps: Data, pps: Data) -> Data {
        sequence = 1
        decodeTime = 0
        var brand = four("isom")
        brand.append(i32(0x200))
        brand.append(four("isom"))
        brand.append(four("iso6"))
        brand.append(four("mp41"))
        return box("ftyp", brand) + movie(sps: sps, pps: pps)
    }

    func media(samples: [(data: Data, keyframe: Bool)]) -> Data {
        guard !samples.isEmpty else { return Data() }
        let durations = samples.map { _ in ticksPerFrame }
        let sizes = samples.map { $0.data.count }
        let flags = samples.map { $0.keyframe ? 0x02000000 : 0x01040000 }
        let mfhd = box("mfhd", i32(0) + i32(sequence))
        let tfhd = box("tfhd", i32(0x00020000) + i32(1))
        let tfdt = box("tfdt", i32(0x01000000) + i64(decodeTime))
        func trun(offset: Int) -> Data {
            var body = i32(0x00000701) + i32(samples.count) + i32(offset)
            for index in samples.indices {
                body.append(i32(durations[index]))
                body.append(i32(sizes[index]))
                body.append(i32(flags[index]))
            }
            return box("trun", body)
        }
        let draft = box("moof", mfhd + box("traf", tfhd + tfdt + trun(offset: 0)))
        let dataOffset = draft.count + 8
        let moof = box("moof", mfhd + box("traf", tfhd + tfdt + trun(offset: dataOffset)))
        var payload = Data()
        for sample in samples { payload.append(sample.data) }
        durations.forEach { decodeTime += UInt64($0) }
        sequence += 1
        return moof + box("mdat", payload)
    }

    private func movie(sps: Data, pps: Data) -> Data {
        let mvhd = box("mvhd",
            i32(0) + i32(0) + i32(0) + i32(90_000) + i32(0) + i32(0x00010000) + i16(0x0100) +
            i16(0) + i32(0) + i32(0) + matrix() + i32(0) + i32(0) + i32(0) + i32(0) + i32(0) + i32(0) + i32(2))
        let tkhd = box("tkhd",
            i32(0x00000007) + i32(0) + i32(0) + i32(1) + i32(0) + i32(0) + i32(0) + i32(0) +
            i16(0) + i16(0) + i16(0) + i16(0) + matrix() + i32(width << 16) + i32(height << 16))
        let mdhd = box("mdhd", i32(0) + i32(0) + i32(0) + i32(90_000) + i32(0) + i16(0x55C4) + i16(0))
        let hdlr = box("hdlr", i32(0) + i32(0) + four("vide") + i32(0) + i32(0) + i32(0) + cstr("VideoHandler"))
        let vmhd = box("vmhd", i32(1) + i16(0) + i16(0) + i16(0) + i16(0))
        let dinf = box("dinf", box("dref", i32(0) + i32(1) + box("url ", i32(1))))
        var avc1Body = Data(count: 78)
        avc1Body[7] = 1
        put16(&avc1Body, 24, width)
        put16(&avc1Body, 26, height)
        put32(&avc1Body, 28, 0x00480000)
        put32(&avc1Body, 32, 0x00480000)
        put16(&avc1Body, 40, 1)
        put16(&avc1Body, 74, 0x0018)
        put16(&avc1Body, 76, 0xFFFF)
        let profile = sps.count > 1 ? sps[sps.startIndex + 1] : 0x42
        let compat = sps.count > 2 ? sps[sps.startIndex + 2] : 0
        let level = sps.count > 3 ? sps[sps.startIndex + 3] : 0x1E
        var avcC = Data([1, profile, compat, level, 0xFF, 0xE1])
        avcC.append(i16(sps.count))
        avcC.append(sps)
        avcC.append(1)
        avcC.append(i16(pps.count))
        avcC.append(pps)
        let avc1 = box("avc1", avc1Body + box("avcC", avcC))
        let stbl = box("stbl",
            box("stsd", i32(0) + i32(1) + avc1) +
            box("stts", i32(0) + i32(0)) +
            box("stsc", i32(0) + i32(0)) +
            box("stsz", i32(0) + i32(0) + i32(0)) +
            box("stco", i32(0) + i32(0)))
        let minf = box("minf", vmhd + dinf + stbl)
        let trak = box("trak", tkhd + box("mdia", mdhd + hdlr + minf))
        let trex = box("trex", i32(0) + i32(1) + i32(1) + i32(ticksPerFrame) + i32(0) + i32(0))
        return box("moov", mvhd + trak + box("mvex", trex))
    }

    static func codecString(sps: Data) -> String {
        guard sps.count >= 4 else { return "avc1.42E01E" }
        let bytes = [UInt8](sps)
        return String(format: "avc1.%02X%02X%02X", bytes[1], bytes[2], bytes[3])
    }
}

private func matrix() -> Data {
    i32(0x00010000) + i32(0) + i32(0) + i32(0) + i32(0x00010000) + i32(0) + i32(0) + i32(0) + i32(0x40000000)
}

private func box(_ type: String, _ body: Data) -> Data {
    i32(8 + body.count) + four(type) + body
}

private func four(_ value: String) -> Data { Data(value.utf8) }

private func cstr(_ value: String) -> Data { Data((value + "\u{0}").utf8) }

private func put16(_ target: inout Data, _ offset: Int, _ value: Int) {
    target[offset] = UInt8((value >> 8) & 0xFF)
    target[offset + 1] = UInt8(value & 0xFF)
}

private func put32(_ target: inout Data, _ offset: Int, _ value: Int) {
    target[offset] = UInt8((value >> 24) & 0xFF)
    target[offset + 1] = UInt8((value >> 16) & 0xFF)
    target[offset + 2] = UInt8((value >> 8) & 0xFF)
    target[offset + 3] = UInt8(value & 0xFF)
}

private func i16(_ value: Int) -> Data {
    Data([UInt8((value >> 8) & 0xFF), UInt8(value & 0xFF)])
}

private func i32(_ value: Int) -> Data {
    let bits = UInt32(bitPattern: Int32(truncatingIfNeeded: value)).bigEndian
    return withUnsafeBytes(of: bits) { Data($0) }
}

private func i64(_ value: UInt64) -> Data {
    var bits = value.bigEndian
    return withUnsafeBytes(of: &bits) { Data($0) }
}

private func + (lhs: Data, rhs: Data) -> Data {
    var copy = lhs
    copy.append(rhs)
    return copy
}
