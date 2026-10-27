// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Decode bounds before allocating pixels. Only inert raster images enter app-private storage. */
object AppearanceImages {
    const val MAX_UPLOAD = 8 * 1024 * 1024
    const val MAX_STORED = 2 * 1024 * 1024
    const val MAX_PIXELS = 16_000_000L
    private const val WIDTH = 1920
    private const val HEIGHT = 1080

    private fun mime(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }

    fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(output.size().toLong() + n <= MAX_UPLOAD) { "图片不能超过 8 MB" }
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    private fun bounds(bytes: ByteArray): BitmapFactory.Options {
        require(bytes.isNotEmpty() && bytes.size <= MAX_UPLOAD) { "图片不能超过 8 MB" }
        val mime = mime(bytes) ?: error("支持 JPEG、PNG 和 WebP 图片")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0 &&
            options.outWidth.toLong() * options.outHeight <= MAX_PIXELS) { "请选择不超过 1600 万像素的图片" }
        require(options.outMimeType == null || options.outMimeType == mime) { "图片格式不匹配" }
        // Some platform decoders omit MIME metadata; the validated magic bytes remain authoritative.
        options.outMimeType = mime
        return options
    }

    fun validateStored(bytes: ByteArray) {
        require(bytes.size <= MAX_STORED && bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) { "背景文件无效" }
        val options = bounds(bytes)
        require(options.outMimeType == "image/jpeg" && options.outWidth <= WIDTH && options.outHeight <= HEIGHT) { "背景尺寸无效" }
        // Bounds alone are insufficient for a truncated or malicious image.
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("背景无法解码")
        decoded.recycle()
    }

    fun prepare(bytes: ByteArray): ByteArray {
        val info = bounds(bytes)
        var sample = 1
        while (info.outWidth / sample > WIDTH * 2 || info.outHeight / sample > HEIGHT * 2 ||
            info.outWidth.toLong() * info.outHeight / sample / sample > 4_000_000) sample *= 2
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("图片无法解码")
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24 && info.outMimeType == "image/jpeg") {
                val orientation = runCatching {
                    android.media.ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
                }.getOrDefault(1)
                val matrix = Matrix().apply {
                    when (orientation) {
                        2 -> setScale(-1f, 1f)
                        3 -> setRotate(180f)
                        4 -> setScale(1f, -1f)
                        5 -> { setRotate(90f); postScale(-1f, 1f) }
                        6 -> setRotate(90f)
                        7 -> { setRotate(270f); postScale(-1f, 1f) }
                        8 -> setRotate(270f)
                    }
                }
                if (!matrix.isIdentity) {
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
                }
            }
            val ratio = minOf(1f, WIDTH.toFloat() / bitmap.width, HEIGHT.toFloat() / bitmap.height)
            if (ratio < 1f) {
                val resized = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt()), true)
                if (resized !== bitmap) { bitmap.recycle(); bitmap = resized }
            }
            for (quality in listOf(88, 75, 60, 45)) {
                val output = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "图片转换失败" }
                if (output.size() <= MAX_STORED) return output.toByteArray()
            }
            error("图片转换后仍超过 2 MB，请选择其他图片")
        } finally { bitmap.recycle() }
    }
}
