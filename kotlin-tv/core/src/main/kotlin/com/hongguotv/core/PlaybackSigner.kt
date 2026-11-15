// SPDX-License-Identifier: GPL-3.0-only
// Adapted from jycao2/hongguoTV 13aa8e7 and LanZhan-Harmony/PhoenixReadApi SignPlayer.
// PhoenixReadApi copyright (c) 2026 LanZhan, MIT. See licenses/PhoenixReadApi-MIT.txt.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Current playback protocol, independent of the signer used for content and accounts. */
internal class PlaybackSigner(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = Math::random,
) {
    fun sign(base: String, values: Map<String, String>, body: ByteArray? = null): Signer.Request {
        val now = clock()
        val timestamp = now / 1000
        val query = Signer.encode(values + ("_rticket" to now.toString()))
        val nonce = ByteArray(11) { (random() * 256).toInt().toByte() }
        val headers = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "x-ss-req-ticket" to now.toString(),
            "x-khronos" to timestamp.toString(),
            "x-gorgon" to gorgon(query, body, timestamp),
            "x-argus" to argus(query, body, timestamp, values.getValue("device_id"), nonce),
            "x-ladon" to ladon(values.getValue("aid"), timestamp, nonce.copyOfRange(2, 6)),
        )
        if (body != null && body.isNotEmpty()) {
            headers["Content-Type"] = "application/json; charset=utf-8"
            headers["x-ss-stub"] = digest("MD5", body).hex().uppercase()
        }
        return Signer.Request("$base?$query", headers, body)
    }

    private fun gorgon(query: String, body: ByteArray?, timestamp: Long): String {
        val data = ByteArray(20)
        digest("MD5", query.toByteArray()).copyInto(data, 0, 0, 4)
        if (body != null && body.isNotEmpty()) digest("MD5", body).copyInto(data, 4, 0, 4)
        data[13] = 6
        data[14] = 11
        data[15] = 28
        be32(timestamp.toInt()).copyInto(data, 16)
        for (index in data.indices) data[index] = (data[index].u() xor REQUEST_KEY[index].u()).toByte()
        for (index in data.indices) {
            val swapped = ((data[index].u() shl 4) or (data[index].u() ushr 4)) and 255
            val mixed = swapped xor data[(index + 1) % data.size].u()
            data[index] = ((Integer.reverse(mixed) ushr 24) xor 255 xor data.size).toByte()
        }
        return (hex("8404401c0000") + data).hex()
    }

    private fun writeVarint(output: ByteArrayOutputStream, value: Long) {
        var remaining = value
        while (remaining > 127) {
            output.write(((remaining and 127) or 128).toInt())
            remaining = remaining ushr 7
        }
        output.write(remaining.toInt())
    }

    private fun number(output: ByteArrayOutputStream, field: Long, value: Long) {
        writeVarint(output, field shl 3)
        writeVarint(output, value)
    }

    private fun bytes(output: ByteArrayOutputStream, field: Long, value: ByteArray) {
        writeVarint(output, (field shl 3) or 2)
        writeVarint(output, value.size.toLong())
        output.write(value)
    }

    private fun text(output: ByteArrayOutputStream, field: Long, value: String) =
        bytes(output, field, value.toByteArray())

    private fun argus(
        query: String,
        body: ByteArray?,
        timestamp: Long,
        deviceId: String,
        nonce: ByteArray,
    ): String {
        val bodyHash = sm3(if (body == null || body.isEmpty()) ByteArray(16) else digest("MD5", body))
        val queryHash = sm3(query.toByteArray())
        val counters = ByteArrayOutputStream().apply {
            number(this, 1, 1)
            number(this, 2, 1)
            number(this, 3, 1)
            number(this, 7, 3348294860L)
        }
        val environment = ByteArrayOutputStream().apply {
            text(this, 1, "25053RT47C")
            text(this, 2, "16")
            text(this, 3, "update_64")
            number(this, 4, 2147483648L)
        }
        val message = ByteArrayOutputStream().apply {
            number(this, 1, 0x20200929L * 2)
            number(this, 2, 2)
            number(this, 3, (nonce.i32(6, true) and 0x7fffffff).toLong())
            text(this, 4, "8662")
            text(this, 5, deviceId)
            text(this, 6, "1611921764")
            text(this, 7, "7.3.5.32")
            text(this, 8, "v04.07.01-ml-android")
            number(this, 9, 135135744)
            bytes(this, 10, ByteArray(8))
            number(this, 11, 0)
            number(this, 12, timestamp * 2)
            bytes(this, 13, bodyHash.copyOf(6))
            bytes(this, 14, queryHash.copyOf(6))
            bytes(this, 15, counters.toByteArray())
            bytes(this, 16, ByteArray(0))
            text(this, 20, "none")
            number(this, 21, 738)
            bytes(this, 23, environment.toByteArray())
            number(this, 25, 2)
        }
        val data = pad(message.toByteArray())
        val rounds = LongArray(72)
        for (index in 0..3) rounds[index] = ARGUS_DERIVED.i64(index * 8, true)
        for (index in 4 until rounds.size) {
            var mixed = java.lang.Long.rotateRight(rounds[index - 1], 3) xor rounds[index - 3]
            mixed = mixed xor java.lang.Long.rotateRight(mixed, 1)
            val bit = (0x3dc94c3a046d678bL ushr ((index - 4) % 62)) and 1L
            rounds[index] = rounds[index - 4].inv() xor mixed xor (bit xor 3L)
        }
        for (offset in data.indices step 16) {
            var left = data.i64(offset, true)
            var right = data.i64(offset + 8, true)
            for (round in rounds) {
                val next = left xor
                    (java.lang.Long.rotateLeft(right, 1) and java.lang.Long.rotateLeft(right, 8)) xor
                    java.lang.Long.rotateLeft(right, 2) xor round
                left = right
                right = next
            }
            le64(left).copyInto(data, offset)
            le64(right).copyInto(data, offset + 8)
        }
        val mask = hex("d04ffdffd04ffdff")
        for (index in data.indices) data[index] = (data[index].u() xor mask[index % mask.size].u()).toByte()
        val combined = (mask + data).reversedArray()
        val container = pad(hex("a6e783ee7001100918") + combined + hex("567b"))
        val encrypted = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(digest("MD5", ARGUS_KEY.copyOfRange(0, 16)), "AES"),
                IvParameterSpec(digest("MD5", ARGUS_KEY.copyOfRange(16, 32))),
            )
            doFinal(container)
        }
        return (byteArrayOf(0x3c, 0xcc.toByte()) + encrypted).base64()
    }

    private fun ladon(aid: String, timestamp: Long, prefix: ByteArray): String {
        val ascii = digest("MD5", prefix + aid.toByteArray()).hex().toByteArray(Charsets.US_ASCII)
        var left = ascii.i64(0, true)
        var right = ascii.i64(8, true)
        val queue = ArrayDeque(listOf(ascii.i64(16, true), ascii.i64(24, true)))
        val rounds = LongArray(34)
        for (index in rounds.indices) {
            rounds[index] = left
            val mixed = (java.lang.Long.rotateRight(right, 8) + left) xor index.toLong()
            queue.addLast(mixed)
            left = mixed xor java.lang.Long.rotateLeft(left, 3)
            right = queue.removeFirst()
        }
        val data = pad("$timestamp-1611921764-$aid".toByteArray())
        for (offset in data.indices step 16) {
            left = data.i64(offset, true)
            right = data.i64(offset + 8, true)
            for (round in rounds) {
                right = round xor (left + java.lang.Long.rotateRight(right, 8))
                left = right xor java.lang.Long.rotateLeft(left, 3)
            }
            le64(left).copyInto(data, offset)
            le64(right).copyInto(data, offset + 8)
        }
        return (prefix + data).base64()
    }

    private fun pad(data: ByteArray): ByteArray {
        val length = 16 - data.size % 16
        return data + ByteArray(length) { length.toByte() }
    }

    companion object {
        const val USER_AGENT =
            "com.phoenix.read/73532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"
        private val REQUEST_KEY = hex("44b9b9d9a4aef9fca493aa757ca3c2c4a496938f")
        private val ARGUS_KEY = hex("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c")
        private val ARGUS_DERIVED = sm3(ARGUS_KEY + hex("3ccc567b") + ARGUS_KEY)
    }
}
