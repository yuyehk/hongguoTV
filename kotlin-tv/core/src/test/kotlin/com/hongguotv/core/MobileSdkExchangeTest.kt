package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobileSdkExchangeTest {
    private val fixture = JSONObject(javaClass.getResource("/mobile-sdk-exchange.json")!!.readText())
    private val common = hex(fixture.getString("common"))
    private val inner = hex(fixture.getString("inner"))
    private val nonce = hex(fixture.getString("nonce"))

    @Test fun requestPacketsMatchIndependentPythonOracle() {
        rows("requests") { row ->
            val plaintext = hex(row.getString("plain"))
            val packet = MobileSdkExchange.request(plaintext, fixture.getLong("milliseconds"), common, inner, nonce, fixture.getInt("entropyByte"))
            assertEquals("plaintext bytes ${plaintext.size}", row.getString("packet"), packet.hex())
            assertEquals(row.getString("ciphertext"), MobileSdkExchange.encryptPayload(plaintext, common, inner, nonce, 171).hex())
            assertArrayEquals(plaintext, MobileSdkExchange.decryptPayload(hex(row.getString("ciphertext")), common, inner))
        }
        assertEquals(fixture.getString("common"), common.hex())
        assertEquals(fixture.getString("inner"), inner.hex())
    }

    @Test fun xteaMatchesIndependentVectorsForEveryCycleCountAndUnsignedNonce() {
        rows("xtea") { row ->
            val plaintext = hex(row.getString("plain"))
            val encrypted = hex(row.getString("ciphertext"))
            val iv = hex(row.getString("nonce"))
            assertArrayEquals(encrypted, MobileSdkExchange.xteaChain(plaintext, inner, iv))
            assertArrayEquals(plaintext, MobileSdkExchange.xteaChain(encrypted, inner, iv, decrypt = true))
        }
    }

    @Test fun parsesIndependentReadyAndContinuationPacketsWithoutLeakingToken() {
        rows("responses") { row ->
            val result = MobileSdkExchange.response(hex(row.getString("packet")), common, inner)
            assertEquals(row.getInt("status"), result.status)
            assertEquals(row.getString("token"), result.token)
            assertFalse(result.toString().contains(result.token))
            assertTrue(result.toString().contains("<redacted>"))
        }
    }

    @Test fun responsePaddingIsNotMistakenForEstablishedRequestCrc() {
        val row = fixture.getJSONObject("responseWithoutRequestCrc")
        val ciphertext = hex(row.getString("ciphertext"))
        rejects("request CRC") { MobileSdkExchange.decryptPayload(ciphertext, common, inner) }
        assertArrayEquals(hex(row.getString("plain")), MobileSdkExchange.decryptPayload(ciphertext, common, inner, responseFrame = true))
        assertEquals(200, MobileSdkExchange.response(hex(row.getString("packet")), common, inner).status)
    }

    @Test fun rejectsInflationOverflowWrongDeclaredSizesAndTrailingCompressedBytes() {
        rows("invalidCiphertexts") { row ->
            rejects(row.getString("name")) {
                MobileSdkExchange.decryptPayload(hex(row.getString("ciphertext")), common, inner, responseFrame = true)
            }
        }
    }

    @Test fun rejectsDuplicateMissingMalformedAndUnexpectedSessionFields() {
        rows("invalidResponses") { row ->
            rejects(row.getString("name")) { MobileSdkExchange.response(hex(row.getString("packet")), common, inner) }
        }
    }

    @Test fun strictWireParserRejectsTruncationOverflowWrongTypesAndAmbiguousCiphertext() {
        val cipher = hex(fixture.getJSONArray("requests").getJSONObject(1).getString("ciphertext"))
        val header = number(1, 0x20120312L * 2) + number(2, 2)
        val invalid = listOf(
            byteArrayOf(), hex("80"), hex("80808080808080808002"), hex("00"), hex("0b"),
            bytes(1, byteArrayOf(1)) + number(2, 2),
            header + number(1, 123), header + number(6, 1),
            header + bytes(6, cipher) + bytes(6, cipher),
            header + byteArrayOf(0x32, 0x7f, 1),
            header + ByteArrayOutputStream().apply { repeat(513) { write(number(9, 0)) } }.toByteArray(),
        )
        invalid.forEachIndexed { index, packet -> rejects("wire case $index") { MobileSdkExchange.response(packet, common, inner) } }
        val error = rejects("explicit refusal") { MobileSdkExchange.response(header + number(3, 13), common, inner) }
        assertEquals("sdk-exchange-refused:-7", error.message)
    }

    @Test fun acceptsConsistentHeaderRepeatsAndSkipsBoundedUnknownFields() {
        val packet = hex(fixture.getJSONArray("responses").getJSONObject(0).getString("packet"))
        val withMetadata = number(1, 0x20120312L * 2) + number(9, 7) + bytes(10, byteArrayOf(1, 2)) + packet
        assertEquals(200, MobileSdkExchange.response(withMetadata, common, inner).status)
    }

    @Test fun validatesInputMaterialsTimeNonceAndPayloadLimits() {
        val max = ByteArray(65_536) { (it % 251).toByte() }
        val encrypted = MobileSdkExchange.encryptPayload(max, common, inner, nonce, 255)
        assertArrayEquals(max, MobileSdkExchange.decryptPayload(encrypted, common, inner))
        rejects("plaintext limit") { MobileSdkExchange.encryptPayload(ByteArray(65_537), common, inner, nonce, 0) }
        rejects("common key") { MobileSdkExchange.encryptPayload(byteArrayOf(), ByteArray(31), inner, nonce, 0) }
        rejects("inner key") { MobileSdkExchange.encryptPayload(byteArrayOf(), common, ByteArray(32), nonce, 0) }
        rejects("nonce") { MobileSdkExchange.encryptPayload(byteArrayOf(), common, inner, ByteArray(3), 0) }
        rejects("entropy") { MobileSdkExchange.encryptPayload(byteArrayOf(), common, inner, nonce, 256) }
        rejects("negative time") { MobileSdkExchange.request(byteArrayOf(), -1, common, inner, nonce, 0) }
        rejects("ciphertext block") { MobileSdkExchange.decryptPayload(byteArrayOf(1), common, inner) }
        rejects("ciphertext padding") { MobileSdkExchange.decryptPayload(ByteArray(16), common, inner) }
        rejects("packet limit") { MobileSdkExchange.response(ByteArray(131_073), common, inner) }
    }

    @Test fun staticProtocolMaterialsMatchHashVerifiedOfficialInputsAndReturnCopies() {
        val expected = fixture.getJSONObject("materials")
        fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).hex()
        assertEquals(expected.getString("frameSha256"), sha256(MobileV5Constants.magic))
        assertEquals(expected.getString("signingKeySha256"), sha256(MobileV5Constants.signKey))
        assertEquals(expected.getString("substitutionsSha256"), sha256(MobileV5Constants.sbox))
        assertEquals(expected.getString("exchangeKeySha256"), sha256(MobileSdkMaterials.exchangeKey))
        assertEquals(expected.getString("exchangeInnerKeySha256"), sha256(MobileSdkMaterials.exchangeInnerKey))
        assertEquals(32, MobileSdkMaterials.exchangeKey.size)
        assertEquals(16, MobileSdkMaterials.exchangeInnerKey.size)
        assertEquals("!notset!", MobileSdkMaterials.unavailable)
        val isolated = MobileSdkMaterials.exchangeKey
        isolated[0] = (isolated[0].toInt() xor 1).toByte()
        assertEquals(expected.getString("exchangeKeySha256"), sha256(MobileSdkMaterials.exchangeKey))
    }

    private fun rows(name: String, block: (JSONObject) -> Unit) {
        val rows = fixture.getJSONArray(name)
        for (index in 0 until rows.length()) block(rows.getJSONObject(index))
    }

    private fun rejects(name: String, block: () -> Unit): IllegalArgumentException {
        try { block() } catch (error: IllegalArgumentException) { return error }
        throw AssertionError("Expected rejection: $name")
    }

    private fun number(field: Int, value: Long) = varint(field.toLong() shl 3) + varint(value)
    private fun bytes(field: Int, value: ByteArray) = varint((field.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
    private fun varint(value: Long): ByteArray = ByteArrayOutputStream().apply {
        var remaining = value
        while (remaining and -128L != 0L) { write((remaining and 127).toInt() or 128); remaining = remaining ushr 7 }
        write(remaining.toInt())
    }.toByteArray()
}
