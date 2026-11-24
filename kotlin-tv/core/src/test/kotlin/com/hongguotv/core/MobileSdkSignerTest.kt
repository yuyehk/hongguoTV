// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobileSdkSignerTest {
    private fun fixture() = JSONObject(javaClass.getResource("/mobile-sdk-signer.json")!!.readText())
        .getJSONArray("vectors")

    private fun JSONObject.strings(): Map<String, String> = keys().asSequence().associateWith(::getString)

    private fun device() = fixture().getJSONObject(0).getJSONObject("device").strings()
    private fun cookies() = fixture().getJSONObject(0).getJSONObject("cookies").strings()
    private fun signer(now: Long) = MobileSdkSigner({ now }, { ByteArray(it) }, processId = 12345)

    @Test fun matchesIndependentCompleteSdkSignerAcrossBootstrapPlaybackAndAllCipherVariants() {
        val rows = fixture()
        assertEquals(8, rows.length())
        val covered = mutableSetOf<Pair<Boolean, Long>>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val case = row.getString("caseId")
            val ms = row.getLong("milliseconds")
            val bootstrap = row.getBoolean("bootstrap")
            covered += bootstrap to ((ms / 1000) and 3)
            val expected = row.getJSONObject("expected")
            val actual = signer(ms).signPrepared(
                row.getJSONObject("device").strings(), row.getJSONObject("cookies").strings(),
                row.getString("query"), decode64(row.getString("bodyBase64")), ms,
                if (row.isNull("token")) null else row.getString("token"),
                row.getLong("previousLadon"), bootstrap,
            )
            assertEquals("$case URL", expected.getString("url"), actual.url)
            assertEquals("$case complete headers", expected.getJSONObject("headers").strings(), actual.headers)
            assertArrayEquals("$case exact signed body", decode64(expected.getString("bodyBase64")), actual.body)
            assertEquals("$case ladon state", expected.getLong("nextLadon"), actual.nextLadon)
        }
        assertEquals(setOf(true, false).flatMap { boot -> (0L..3L).map { boot to it } }.toSet(), covered)
    }

    @Test fun bootstrapBindsItsSelectedTicketToBodyTimestampAndOwnAnonymousDevice() {
        // Start at a second boundary to catch using the original second after ticket selection.
        val now = 1800000000999L
        val currentDevice = device()
        val anonymousCookies = cookies() + ("sessionid" to "synthetic-account-cookie-must-not-be-forwarded")
        for (token in listOf(null, "synthetic-sdk-token")) {
            val request = signer(now).bootstrap(currentDevice, anonymousCookies, token, 0)
            val address = request.url.toHttpUrl()
            assertEquals("https://mssdk.zijieapi.com/sdi/get_token", request.url.substringBefore('?'))
            val ticket = assertBoundedSelectedTicket(request, now)
            assertEquals(currentDevice.getValue("device_id"), address.queryParameter("did"))
            assertEquals(currentDevice.getValue("iid"), address.queryParameter("iid"))
            val outer = wireFields(request.body)
            assertEquals("body and query use one candidate millisecond", ticket * 2, outer.getValue(5))
            val plain = MobileSdkExchange.decryptPayload(outer.getValue(4) as ByteArray,
                MobileSdkMaterials.exchangeKey, MobileSdkMaterials.exchangeInnerKey)
            val payload = wireFields(plain)
            assertEquals(currentDevice.getValue("device_id"), (payload.getValue(8) as ByteArray).toString(Charsets.UTF_8))
            if (token == null) assertFalse(payload.containsKey(2))
            else assertEquals(token, (payload.getValue(2) as ByteArray).toString(Charsets.UTF_8))
            val cookie = request.headers.getValue("Cookie")
            assertTrue(cookie.startsWith("install_id=${currentDevice.getValue("iid")}; "))
            assertTrue(cookie.endsWith("store-region-src=did"))
            assertFalse(cookie.contains("sessionid"))
            assertFalse(cookie.contains("synthetic-account-cookie"))
            assertFalse(request.headers.keys.any { it.equals("Authorization", true) || it.equals("X-TT-Token", true) })
        }
    }

    @Test fun playbackSignsCompressedExactEpisodeAndRetainsSdkRequestContract() {
        val now = 1800000000999L
        val bodies = mutableListOf<ByteArray>()
        for (id in listOf("7000000000000000101", "7000000000000000102")) {
            val request = signer(now).playback(id, device(), cookies(), "synthetic-sdk-token", 17)
            assertEquals("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/player/v1",
                request.url.substringBefore('?'))
            val ticket = assertBoundedSelectedTicket(request, now)
            assertEquals(ticket.toString(), request.headers["X-SS-REQ-TICKET"])
            assertEquals("gzip", request.headers["Content-Encoding"])
            assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
            assertEquals(digest("MD5", request.body).hex().uppercase(), request.headers["X-SS-STUB"])
            val json = JSONObject(GZIPInputStream(ByteArrayInputStream(request.body)).use {
                it.readBytes().toString(Charsets.UTF_8)
            })
            assertEquals("player", json.getString("dr_scene"))
            val mapping = json.getJSONObject("mixed_video_id_map")
            assertEquals(setOf("1004"), mapping.keys().asSequence().toSet())
            assertEquals(1, mapping.getJSONArray("1004").length())
            assertEquals(id, mapping.getJSONArray("1004").getString(0))
            val options = json.getJSONObject("biz_param")
            assertEquals(4, options.getInt("device_level"))
            assertEquals(1024, options.getInt("video_platform"))
            assertTrue(options.getBoolean("need_all_video_definition"))
            assertEquals("did", request.headers["X-TT-Store-Region-Src"])
            assertEquals("cn", request.headers["X-TT-Store-Region"])
            bodies += request.body
        }
        assertFalse("different episodes must not share a signed body", bodies[0].contentEquals(bodies[1]))
    }

    @Test fun rejectsAccountBoundContextAndMalformedForwardedCookieBeforeProducingRequest() {
        val signer = signer(1800000000000)
        assertThrows(IllegalArgumentException::class.java) {
            signer.bootstrap(device(), cookies() + ("store-region-src" to "uid"), null, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            signer.playback("7000000000000000101", device(), cookies() + ("store-region-src" to "uid"), "synthetic-sdk-token", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            signer.bootstrap(device(), cookies() + ("ttreq" to "value; sessionid=other"), null, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            signer.playback("7000000000000000101&other=episode", device(), cookies(), "synthetic-sdk-token", 0)
        }
    }

    @Test fun cancelledBootstrapStopsDuringPreparation() {
        val scope = RequestScope()
        val signer = MobileSdkSigner({ 1800000000000 }, { count -> scope.cancel(); ByteArray(count) }, processId = 12345)
        assertThrows(IOException::class.java) {
            scope.run { signer.bootstrap(device(), cookies(), null, 0) }
        }
    }

    private fun assertBoundedSelectedTicket(request: MobileSdkRequest, original: Long): Long {
        val address = request.url.toHttpUrl()
        val ticket = address.queryParameter("_rticket")!!.toLong()
        assertTrue("ticket search stays within its 1024-candidate budget", ticket in original..original + 1023)
        assertEquals((ticket / 1000).toString(), request.headers["X-Khronos"])
        val branch = MobileV5Signer(mobileV5 = true).branch(address.encodedQuery!!, request.body, (ticket / 1000).toInt())
        assertEquals("selected final bytes use the verified SDK branch", 0, branch)
        return ticket
    }

    // Minimal independent protobuf reader for the two request envelopes, not the production parser.
    private fun wireFields(bytes: ByteArray): Map<Int, Any> {
        var offset = 0
        fun varint(): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                check(offset < bytes.size)
                val value = bytes[offset++].toInt() and 255
                result = result or ((value and 127).toLong() shl shift)
                if (value and 128 == 0) return result
            }
            error("invalid fixture protobuf")
        }
        val fields = linkedMapOf<Int, Any>()
        while (offset < bytes.size) {
            val key = varint().toInt()
            val tag = key ushr 3
            check(!fields.containsKey(tag))
            fields[tag] = when (key and 7) {
                0 -> varint()
                2 -> {
                    val size = varint().toInt()
                    check(size >= 0 && size <= bytes.size - offset)
                    bytes.copyOfRange(offset, offset + size).also { offset += size }
                }
                else -> error("unexpected request protobuf field")
            }
        }
        return fields
    }
}
