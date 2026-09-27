package com.tillrecorder.agent

import java.io.ByteArrayOutputStream

/**
 * Fragmented MP4 for a browser MediaSource buffer.
 * Init is ftyp+moov. Each media piece is moof+mdat and starts on a keyframe.
 * Timescale is 90000. At 15 fps the default sample is 6000 ticks.
 */
class Fmp4Muxer(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val rotationDegrees: Int = 0,
) {
    val ticksPerFrame: Int = 90_000 / frameRate.coerceAtLeast(1)
    private var sequence = 1
    private var decodeTime = 0L

    data class Sample(val data: ByteArray, val keyframe: Boolean, val durationTicks: Int = 0)

    fun start(sps: ByteArray, pps: ByteArray): ByteArray {
        sequence = 1
        decodeTime = 0L
        val brand = four("isom") + i32(0x200) + four("isom") + four("iso6") + four("mp41")
        return box("ftyp", brand) + movie(sps, pps)
    }

    fun media(samples: List<Sample>): ByteArray {
        if (samples.isEmpty()) return ByteArray(0)
        val durations = samples.map { if (it.durationTicks > 0) it.durationTicks else ticksPerFrame }
        val sizes = samples.map { it.data.size }
        val flags = samples.map { if (it.keyframe) 0x02000000 else 0x01040000 }
        val trunBody = i32(0x00000701) + i32(samples.size) + i32(0) +
            samples.indices.fold(ByteArray(0)) { acc, index ->
                acc + i32(durations[index]) + i32(sizes[index]) + i32(flags[index])
            }
        val trun = box("trun", trunBody)
        val tfhd = box("tfhd", i32(0x00020000) + i32(1))
        val tfdt = box("tfdt", i32(0x01000000) + i64(decodeTime))
        val traf = box("traf", tfhd + tfdt + trun)
        val mfhd = box("mfhd", i32(0) + i32(sequence))
        val moofSize = 8 + mfhd.size + traf.size
        val dataOffset = moofSize + 8
        val patchedTrun = box("trun", i32(0x00000701) + i32(samples.size) + i32(dataOffset) +
            samples.indices.fold(ByteArray(0)) { acc, index ->
                acc + i32(durations[index]) + i32(sizes[index]) + i32(flags[index])
            })
        val patchedTraf = box("traf", tfhd + tfdt + patchedTrun)
        val moof = box("moof", mfhd + patchedTraf)
        val payload = samples.fold(ByteArray(0)) { acc, sample -> acc + sample.data }
        val mdat = box("mdat", payload)
        durations.forEach { decodeTime += it.toLong() }
        sequence += 1
        return moof + mdat
    }

    private fun movie(sps: ByteArray, pps: ByteArray): ByteArray {
        val degrees = ((rotationDegrees % 360) + 360) % 360
        val displayWidth = if (degrees == 90 || degrees == 270) height else width
        val displayHeight = if (degrees == 90 || degrees == 270) width else height
        val mvhd = box(
            "mvhd",
            i32(0) + i32(0) + i32(0) + i32(90_000) + i32(0) + i32(0x00010000) + i16(0x0100) +
                i16(0) + i32(0) + i32(0) + matrix(0, width, height) + i32(0) + i32(0) + i32(0) +
                i32(0) + i32(0) + i32(0) + i32(2)
        )
        val tkhd = box(
            "tkhd",
            i32(0x00000007) + i32(0) + i32(0) + i32(1) + i32(0) + i32(0) + i32(0) + i32(0) +
                i16(0) + i16(0) + i16(0) + i16(0) + matrix(degrees, width, height) +
                i32(displayWidth shl 16) + i32(displayHeight shl 16)
        )
        val mdhd = box("mdhd", i32(0) + i32(0) + i32(0) + i32(90_000) + i32(0) + i16(0x55C4) + i16(0))
        val hdlr = box("hdlr", i32(0) + i32(0) + four("vide") + i32(0) + i32(0) + i32(0) + cstr("VideoHandler"))
        val vmhd = box("vmhd", i32(0x00000001) + i16(0) + i16(0) + i16(0) + i16(0))
        val url = box("url ", i32(0x00000001))
        val dref = box("dref", i32(0) + i32(1) + url)
        val dinf = box("dinf", dref)
        val avcC = box(
            "avcC",
            byteArrayOf(
                1.toByte(),
                sps.getOrElse(1) { 0x42.toByte() },
                sps.getOrElse(2) { 0.toByte() },
                sps.getOrElse(3) { 0x1E.toByte() },
                0xFF.toByte(),
                0xE1.toByte()
            ) + i16(sps.size) + sps + byteArrayOf(1.toByte()) + i16(pps.size) + pps
        )
        val avc1Body = ByteArray(78)
        avc1Body[7] = 1.toByte()
        put16(avc1Body, 24, width)
        put16(avc1Body, 26, height)
        put32(avc1Body, 28, 0x00480000)
        put32(avc1Body, 32, 0x00480000)
        put16(avc1Body, 40, 1)
        put16(avc1Body, 74, 0x0018)
        put16(avc1Body, 76, 0xFFFF)
        val avc1 = box("avc1", avc1Body + avcC)
        val stsd = box("stsd", i32(0) + i32(1) + avc1)
        val stts = box("stts", i32(0) + i32(0))
        val stsc = box("stsc", i32(0) + i32(0))
        val stsz = box("stsz", i32(0) + i32(0) + i32(0))
        val stco = box("stco", i32(0) + i32(0))
        val stbl = box("stbl", stsd + stts + stsc + stsz + stco)
        val minf = box("minf", vmhd + dinf + stbl)
        val mdia = box("mdia", mdhd + hdlr + minf)
        val trak = box("trak", tkhd + mdia)
        val trex = box("trex", i32(0) + i32(1) + i32(1) + i32(ticksPerFrame) + i32(0) + i32(0))
        val mvex = box("mvex", trex)
        return box("moov", mvhd + trak + mvex)
    }
}

fun avcCodecString(sps: ByteArray): String {
    if (sps.size < 4) return "avc1.42E01E"
    return "avc1.%02X%02X%02X".format(sps[1].toInt() and 0xff, sps[2].toInt() and 0xff, sps[3].toInt() and 0xff)
}

fun splitAnnexB(data: ByteArray): List<ByteArray> {
    val starts = ArrayList<Int>()
    var i = 0
    while (i + 3 < data.size) {
        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
            starts.add(i + 3)
            i += 3
            continue
        }
        if (i + 4 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
            data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
        ) {
            starts.add(i + 4)
            i += 4
            continue
        }
        i++
    }
    if (starts.isEmpty()) return listOf(data)
    return starts.mapIndexed { index, begin ->
        val end = if (index + 1 < starts.size) startCodeAt(data, starts[index + 1]) else data.size
        if (end > begin) data.copyOfRange(begin, end) else ByteArray(0)
    }.filter { it.isNotEmpty() }
}

fun annexBToAvcc(data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    for (nal in splitAnnexB(data)) {
        if (nal.isEmpty()) continue
        val type = nal[0].toInt() and 0x1f
        if (type == 6 || type == 7 || type == 8 || type == 9) continue
        out.write((nal.size ushr 24) and 0xff)
        out.write((nal.size ushr 16) and 0xff)
        out.write((nal.size ushr 8) and 0xff)
        out.write(nal.size and 0xff)
        out.write(nal)
    }
    return out.toByteArray()
}

private fun startCodeAt(data: ByteArray, payload: Int): Int {
    if (payload >= 4 && data[payload - 1] == 1.toByte() && data[payload - 2] == 0.toByte() &&
        data[payload - 3] == 0.toByte() && data[payload - 4] == 0.toByte()
    ) {
        return payload - 4
    }
    if (payload >= 3 && data[payload - 1] == 1.toByte() && data[payload - 2] == 0.toByte() &&
        data[payload - 3] == 0.toByte()
    ) {
        return payload - 3
    }
    return payload
}

private fun matrix(degrees: Int, width: Int, height: Int): ByteArray {
    val w = width shl 16
    val h = height shl 16
    val values = when (degrees) {
        90 -> intArrayOf(0, 0x00010000, 0, -0x00010000, 0, 0, h, 0, 0x40000000)
        180 -> intArrayOf(-0x00010000, 0, 0, 0, -0x00010000, 0, w, h, 0x40000000)
        270 -> intArrayOf(0, -0x00010000, 0, 0x00010000, 0, 0, 0, w, 0x40000000)
        else -> intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)
    }
    return values.fold(ByteArray(0)) { acc, value -> acc + i32(value) }
}

private fun box(type: String, body: ByteArray): ByteArray = i32(8 + body.size) + four(type) + body

private fun four(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

private fun cstr(value: String): ByteArray = (value + "\u0000").toByteArray(Charsets.US_ASCII)

private fun put16(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value shr 8).toByte()
    target[offset + 1] = value.toByte()
}

private fun put32(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value ushr 24).toByte()
    target[offset + 1] = (value ushr 16).toByte()
    target[offset + 2] = (value ushr 8).toByte()
    target[offset + 3] = value.toByte()
}

private fun i16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

private fun i32(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte()
)

private fun i64(value: Long): ByteArray = byteArrayOf(
    (value ushr 56).toByte(),
    (value ushr 48).toByte(),
    (value ushr 40).toByte(),
    (value ushr 32).toByte(),
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte()
)

private operator fun ByteArray.plus(other: ByteArray): ByteArray {
    val out = ByteArray(size + other.size)
    System.arraycopy(this, 0, out, 0, size)
    System.arraycopy(other, 0, out, size, other.size)
    return out
}
