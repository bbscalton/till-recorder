package com.tillrecorder.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class Fmp4MuxerTest {
    @Test
    fun initIsAFragmentedMovieAndFifteenFps() {
        val muxer = Fmp4Muxer(320, 240, 15)
        assertTrue(90_000 / muxer.ticksPerFrame >= 15)
        assertEquals(6_000, muxer.ticksPerFrame)
        val init = muxer.start(byteArrayOf(0x67, 0x42, 0xE0.toByte(), 0x1E, 0x00), byteArrayOf(0x68, 0xCE.toByte(), 0x06, 0xE2.toByte()))
        assertEquals("ftyp", text(init, 4, 4))
        assertTrue(String(init, StandardCharsets.ISO_8859_1).contains("moov"))
        assertBoxes(init)
        val sample = ByteArray(8)
        sample[3] = 4
        sample[4] = 0x65
        val media = muxer.media(listOf(Fmp4Muxer.Sample(sample, true)))
        assertEquals("moof", text(media, 4, 4))
        assertTrue(String(media, StandardCharsets.ISO_8859_1).contains("mdat"))
        assertBoxes(media)
    }

    @Test
    fun codecStringComesFromTheSps() {
        assertEquals("avc1.42E01E", avcCodecString(byteArrayOf(0x67, 0x42, 0xE0.toByte(), 0x1E)))
    }

    private fun assertBoxes(bytes: ByteArray) {
        fun walk(start: Int, end: Int) {
            var pos = start
            while (pos + 8 <= end) {
                val size = readInt(bytes, pos)
                assertTrue("box at $pos size $size end $end", size >= 8 && pos + size <= end)
                val type = text(bytes, pos + 4, 4)
                if (type in setOf("moov", "trak", "mdia", "minf", "dinf", "stbl", "mvex", "moof", "traf")) {
                    walk(pos + 8, pos + size)
                }
                pos += size
            }
            assertEquals(end, pos)
        }
        walk(0, bytes.size)
    }

    private fun readInt(bytes: ByteArray, pos: Int): Int {
        return ((bytes[pos].toInt() and 0xff) shl 24) or
            ((bytes[pos + 1].toInt() and 0xff) shl 16) or
            ((bytes[pos + 2].toInt() and 0xff) shl 8) or
            (bytes[pos + 3].toInt() and 0xff)
    }

    private fun text(bytes: ByteArray, pos: Int, len: Int): String {
        return String(bytes, pos, len, StandardCharsets.ISO_8859_1)
    }
}
