// SPDX-License-Identifier: GPL-3.0-only
// Adapted from jackotom/hongguo-drama ea5a852f65acdc4c5b11265ca13b03d115096dc7,
// scripts/sdk-playback/vendor/phone_account/phone_sdk_exchange_v5.py.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class MobileSdkExchangeResult(val status: Int, val token: String) {
    override fun toString() = "MobileSdkExchangeResult(status=$status, token=<redacted>)"
}

/** Bounded SDI framing only: no device, session storage, transport or SDK execution. */
internal object MobileSdkExchange {
    private const val MAX_PLAIN = 65_536
    private const val DELTA = -1640531527

    fun request(
        plaintext: ByteArray,
        milliseconds: Long,
        common: ByteArray,
        inner: ByteArray,
        nonce: ByteArray,
        entropyByte: Int,
    ): ByteArray {
        require(milliseconds >= 0) { "invalid-sdk-request-time" }
        val ciphertext = encryptPayload(plaintext, common, inner, nonce, entropyByte)
        return ByteArrayOutputStream().apply {
            number(1, 0x20200422L * 2)
            number(2, 2)
            number(3, 2)
            bytes(4, ciphertext)
            // The wire value is unsigned; unsigned shifts preserve the upper bit.
            number(5, milliseconds * 2)
        }.toByteArray()
    }

    fun response(packet: ByteArray, common: ByteArray, inner: ByteArray): MobileSdkExchangeResult {
        val fields = mutableMapOf<Int, WireField>()
        for (field in wireFields(packet, MAX_PLAIN * 2)) {
            when (field.number) {
                1, 2 -> require(field.encoding == 0 &&
                    (fields[field.number] == null || fields[field.number]?.integer == field.integer)) {
                    "invalid-sdk-response-header"
                }
                3 -> require(field.encoding == 0) { "invalid-sdk-response-status" }
                6 -> require(field.encoding == 2 && !fields.containsKey(6)) {
                    "invalid-sdk-response-ciphertext"
                }
                else -> continue
            }
            fields[field.number] = field
        }
        require(fields[1]?.integer == 0x20120312L * 2 && fields[2]?.integer == 2L) {
            "invalid-sdk-response-header"
        }
        val ciphertext = fields[6]?.data ?: run {
            val status = fields[3]?.integer
            require(status == null) { "sdk-exchange-refused:${signed(status!!)}" }
            throw IllegalArgumentException("missing-sdk-response-ciphertext")
        }
        val decoded = decryptPayload(ciphertext, common, inner, responseFrame = true)
        val result = mutableMapOf<Int, WireField>()
        for (field in wireFields(decoded)) {
            if (field.number !in 1..2) continue
            require(!result.containsKey(field.number) && field.encoding == if (field.number == 1) 2 else 0) {
                "invalid-sdk-session-fields"
            }
            result[field.number] = field
        }
        require(result.keys == setOf(1, 2)) { "incomplete-sdk-session" }
        val status = signed(result.getValue(2).integer)
        require(status == 200L || status == 202L) { "sdk-session-status:$status" }
        val token = result.getValue(1).data!!
        require(token.size in 1..256 && token.all { (it.toInt() and 255) in 33..126 }) {
            "invalid-sdk-session-token"
        }
        return MobileSdkExchangeResult(status.toInt(), token.toString(Charsets.UTF_8))
    }

    internal fun encryptPayload(
        plaintext: ByteArray,
        common: ByteArray,
        inner: ByteArray,
        nonce: ByteArray,
        entropyByte: Int,
    ): ByteArray {
        keys(common, inner)
        require(plaintext.size <= MAX_PLAIN) { "invalid-sdk-plaintext" }
        require(entropyByte in 0..255) { "invalid-sdk-entropy" }
        val compressed = deflate(plaintext).apply { this[0] = 0x78; this[1] = 0x01 }
        val container = little(plaintext.size) + compressed
        val extra = (8 - container.size % 8) % 8
        val position = 1 + (extra + 3) % 4
        val frame = ByteArray(container.size + 8 + extra)
        frame[0] = ((entropyByte and 0xf8) or extra).toByte()
        container.copyInto(frame, position)
        val crcOffset = if (position >= 3) 1 else position + container.size
        val crc = crc16(container)
        frame[crcOffset] = (crc ushr 8).toByte()
        frame[crcOffset + 1] = crc.toByte()
        val encrypted = xteaChain(frame, inner, nonce)
        val wrapped = byteArrayOf((encrypted[0].toInt() xor 3).toByte()) + encrypted + nonce
        return aes(Cipher.ENCRYPT_MODE, wrapped, common)
    }

    internal fun decryptPayload(
        ciphertext: ByteArray,
        common: ByteArray,
        inner: ByteArray,
        responseFrame: Boolean = false,
    ): ByteArray {
        keys(common, inner)
        require(ciphertext.isNotEmpty() && ciphertext.size % 16 == 0 && ciphertext.size <= MAX_PLAIN * 2) {
            "invalid-sdk-ciphertext"
        }
        val wrapped = try {
            aes(Cipher.DECRYPT_MODE, ciphertext, common)
        } catch (_: GeneralSecurityException) {
            throw IllegalArgumentException("invalid-sdk-padding")
        }
        require(wrapped.size >= 13 && (wrapped.size - 5) % 8 == 0 &&
            (wrapped[0].toInt() xor wrapped[1].toInt()) == 3) { "invalid-sdk-outer-frame" }
        val frame = xteaChain(
            wrapped.copyOfRange(1, wrapped.size - 4), inner, wrapped.copyOfRange(wrapped.size - 4, wrapped.size), true,
        )
        val extra = frame[0].toInt() and 7
        val length = frame.size - 8 - extra
        val position = 1 + (extra + 3) % 4
        require(length >= 4) { "invalid-sdk-inner-frame" }
        val container = frame.copyOfRange(position, position + length)
        val crcOffset = if (position >= 3) 1 else position + length
        val actualCrc = ((frame[crcOffset].toInt() and 255) shl 8) or (frame[crcOffset + 1].toInt() and 255)
        // Response padding is not established as a CRC. Keep the request CRC
        // constraint, but validate responses through AES, zlib/Adler32 and protobuf.
        require(responseFrame || actualCrc == crc16(container)) { "invalid-sdk-inner-checksum" }
        val expectedSize = unsigned(littleInt(container, 0))
        require(expectedSize <= MAX_PLAIN) { "sdk-response-too-large" }
        return inflate(container.copyOfRange(4, container.size), expectedSize.toInt())
    }

    internal fun xteaChain(data: ByteArray, key: ByteArray, nonce: ByteArray, decrypt: Boolean = false): ByteArray {
        require(data.isNotEmpty() && data.size % 8 == 0 && data.size <= MAX_PLAIN * 2) { "invalid-xtea-blocks" }
        require(key.size == 16 && nonce.size == 4) { "invalid-xtea-materials" }
        val keyWords = IntArray(4) { bigInt(key, it * 4) }
        val cycles = 32 + 8 * (unsigned(littleInt(nonce, 0)) % 5).toInt()
        var previous = nonce + little(0x20200427)
        val result = ByteArray(data.size)
        for (offset in data.indices step 8) {
            val source = data.copyOfRange(offset, offset + 8)
            val block = if (decrypt) source else ByteArray(8) { (source[it].toInt() xor previous[it].toInt()).toByte() }
            val words = intArrayOf(bigInt(block, 0), bigInt(block, 4))
            val steps = if (decrypt) cycles * 2 - 1 downTo 0 else 0 until cycles * 2
            for (step in steps) {
                val target = step and 1
                val total = (step + 1) / 2 * DELTA
                val other = words[1 - target]
                val index = (total ushr (11 * target)) and 3
                val mix = (((other shl 4) xor (other ushr 5)) + other) xor (total + keyWords[index])
                words[target] += if (decrypt) -mix else mix
            }
            val transformed = big(words[0]) + big(words[1])
            for (index in 0 until 8) {
                result[offset + index] = if (decrypt) (transformed[index].toInt() xor previous[index].toInt()).toByte()
                else transformed[index]
            }
            previous = if (decrypt) source else transformed
        }
        return result
    }

    private fun keys(common: ByteArray, inner: ByteArray) {
        require(common.size == 32 && inner.size == 16) { "invalid-sdk-exchange-materials" }
    }

    private fun aes(mode: Int, data: ByteArray, common: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(mode, SecretKeySpec(common.copyOfRange(0, 16), "AES"), IvParameterSpec(common.copyOfRange(16, 32)))
            doFinal(data)
        }

    private fun deflate(data: ByteArray): ByteArray {
        val encoder = Deflater()
        return try {
            encoder.setInput(data)
            encoder.finish()
            ByteArrayOutputStream().apply {
                val buffer = ByteArray(4096)
                while (!encoder.finished()) {
                    val count = encoder.deflate(buffer)
                    check(count > 0) { "sdk-compression-stalled" }
                    write(buffer, 0, count)
                }
            }.toByteArray()
        } finally {
            encoder.end()
        }
    }

    private fun inflate(data: ByteArray, expectedSize: Int): ByteArray {
        val decoder = Inflater()
        return try {
            decoder.setInput(data)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(minOf(4096, expectedSize + 1))
            while (!decoder.finished()) {
                val count = decoder.inflate(buffer)
                require(output.size() + count <= expectedSize) { "invalid-sdk-decoded-size" }
                output.write(buffer, 0, count)
                require(count > 0 || decoder.finished()) { "invalid-sdk-decoded-size" }
            }
            require(output.size() == expectedSize && decoder.remaining == 0) { "invalid-sdk-decoded-size" }
            output.toByteArray()
        } catch (_: DataFormatException) {
            throw IllegalArgumentException("invalid-sdk-compression")
        } finally {
            decoder.end()
        }
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0
        for (value in data) {
            crc = crc xor ((value.toInt() and 255) shl 8)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x1021 else 0) and 0xffff }
        }
        return crc
    }

    private data class WireField(val number: Int, val encoding: Int, val integer: Long = 0, val data: ByteArray? = null)

    private fun wireFields(data: ByteArray, limit: Int = 8192): List<WireField> {
        require(data.size <= limit) { "invalid-sdk-wire-data" }
        var cursor = 0
        fun integer(): Long {
            var result = 0L
            for (part in 0 until 10) {
                require(cursor < data.size) { "truncated-sdk-wire-number" }
                val byte = data[cursor++].toInt() and 255
                require(part != 9 || byte <= 1) { "overflow-sdk-wire-number" }
                result = result or ((byte and 127).toLong() shl (7 * part))
                if (byte < 128) return result
            }
            throw IllegalArgumentException("invalid-sdk-wire-number")
        }
        val rows = mutableListOf<WireField>()
        while (cursor < data.size) {
            val tag = integer()
            val number = tag ushr 3
            val encoding = (tag and 7).toInt()
            require(number in 1 until (1L shl 29)) { "invalid-sdk-wire-field" }
            val field = when (encoding) {
                0 -> WireField(number.toInt(), encoding, integer())
                1, 2, 5 -> {
                    val size = if (encoding == 2) integer() else if (encoding == 1) 8L else 4L
                    require(size >= 0 && size <= data.size - cursor) { "truncated-sdk-wire-field" }
                    val value = data.copyOfRange(cursor, cursor + size.toInt())
                    cursor += size.toInt()
                    WireField(number.toInt(), encoding, data = value)
                }
                else -> throw IllegalArgumentException("unsupported-sdk-wire-encoding")
            }
            rows += field
            require(rows.size <= 512) { "too-many-sdk-wire-fields" }
        }
        return rows
    }

    private fun ByteArrayOutputStream.number(field: Int, value: Long) { varint(field.toLong() shl 3); varint(value) }
    private fun ByteArrayOutputStream.bytes(field: Int, value: ByteArray) {
        varint((field.toLong() shl 3) or 2); varint(value.size.toLong()); write(value)
    }
    private fun ByteArrayOutputStream.varint(value: Long) {
        var rest = value
        while (rest and -128L != 0L) { write((rest and 127).toInt() or 128); rest = rest ushr 7 }
        write(rest.toInt())
    }
    private fun signed(value: Long) = (value ushr 1) xor -(value and 1)
    private fun unsigned(value: Int) = value.toLong() and 0xffffffffL
    private fun little(value: Int) = ByteArray(4) { (value ushr (8 * it)).toByte() }
    private fun big(value: Int) = ByteArray(4) { (value ushr (24 - 8 * it)).toByte() }
    private fun littleInt(data: ByteArray, offset: Int) =
        (0 until 4).fold(0) { value, index -> value or ((data[offset + index].toInt() and 255) shl (8 * index)) }
    private fun bigInt(data: ByteArray, offset: Int) =
        (0 until 4).fold(0) { value, index -> value or ((data[offset + index].toInt() and 255) shl (24 - 8 * index)) }
}
