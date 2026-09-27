package com.tillrecorder.agent

import android.graphics.Bitmap
import android.media.Image
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

fun sampleLuma(image: Image, step: Int = 8): ByteArray {
    val plane = image.planes[0]
    return sampleLuma(
        buffer = plane.buffer,
        width = image.width,
        height = image.height,
        rowStride = plane.rowStride,
        pixelStride = plane.pixelStride,
        step = step,
    )
}

fun sampleLuma(
    buffer: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
    step: Int,
): ByteArray {
    val columns = width / step
    val rows = height / step
    if (columns <= 0 || rows <= 0) return ByteArray(0)
    val samples = ByteArray(columns * rows)
    var index = 0
    for (row in 0 until rows) {
        val y = row * step
        for (column in 0 until columns) {
            val offset = y * rowStride + (column * step) * pixelStride
            if (offset + 2 >= buffer.limit()) {
                samples[index++] = 0
                continue
            }
            val red = buffer.get(offset).toInt() and 0xff
            val green = buffer.get(offset + 1).toInt() and 0xff
            val blue = buffer.get(offset + 2).toInt() and 0xff
            samples[index++] = ((red * 3 + green * 6 + blue) / 10).toByte()
        }
    }
    return samples
}

fun sampleLuma(bitmap: Bitmap, step: Int = 8): ByteArray {
    val columns = bitmap.width / step
    val rows = bitmap.height / step
    if (columns <= 0 || rows <= 0) return ByteArray(0)
    val samples = ByteArray(columns * rows)
    var index = 0
    for (row in 0 until rows) {
        val y = row * step
        for (column in 0 until columns) {
            val pixel = bitmap.getPixel(column * step, y)
            val red = (pixel shr 16) and 0xff
            val green = (pixel shr 8) and 0xff
            val blue = pixel and 0xff
            samples[index++] = ((red * 3 + green * 6 + blue) / 10).toByte()
        }
    }
    return samples
}

fun jpegBytes(bitmap: Bitmap, quality: Int = 55): ByteArray {
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
    return output.toByteArray()
}

fun jpegBytes(image: Image, quality: Int = 55): ByteArray {
    val plane = image.planes[0]
    val bitmap = bitmapFromRgba(
        buffer = plane.buffer,
        width = image.width,
        height = image.height,
        rowStride = plane.rowStride,
        pixelStride = plane.pixelStride,
    )
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
    bitmap.recycle()
    return output.toByteArray()
}

private fun bitmapFromRgba(
    buffer: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val offset = y * rowStride + x * pixelStride
            if (offset + 3 >= buffer.limit()) continue
            val red = buffer.get(offset).toInt() and 0xff
            val green = buffer.get(offset + 1).toInt() and 0xff
            val blue = buffer.get(offset + 2).toInt() and 0xff
            val alpha = buffer.get(offset + 3).toInt() and 0xff
            pixels[y * width + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}
