// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.util.zip.GZIPInputStream
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CurrentAppPlaybackTest {
    private val device = mapOf("iid" to "11", "device_id" to "22", "cdid" to "synthetic")
    private val unsigned = MobilePlaybackSigner { url, _, _ -> MobileSignedRequest(url, emptyMap()) }
    private val preloadPath = "/novel/player/multi_video_model/preload/v1"
    private fun model(codec: String = "h264", fallback: String? = null) = JSONObject()
        .put("video_list", JSONObject().put("v", JSONObject().put("codec_type", codec)
            .put("main_url", "https://example.test/video").put("vwidth", 1280).put("vheight", 720)))
        .apply { if (fallback != null) put("fallback_api", fallback) }
    private fun envelope(model: JSONObject = model(), id: String = "123") = JSONObject().put("code", 0)
        .put("data", JSONObject().put("1004", JSONObject().put(id, JSONObject().put("video_model", model)))).toString()
    private fun vod(model: JSONObject) = JSONObject().put("code", 0)
        .put("video_info", JSONObject().put("data", model)).toString()
    private fun client(reply: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (status, text) = reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
            .message("fixture").body(text.toResponseBody()).build()
    }.build()

    @Test fun currentAppRequestBindsTheExactGzipProfileAndKeepsAccountHeadersOut() {
        var signedBytes = byteArrayOf()
        val signing = MobilePlaybackSigner { url, body, _ ->
            signedBytes = checkNotNull(body)
            MobileSignedRequest(url, mapOf("x-medusa" to "fixture", "Cookie" to "must-not-forward", "Cookie2" to "must-not-forward"))
        }
        var requests = 0
        val http = client { request ->
            requests++
            assertEquals("POST", request.method)
            assertEquals("reading.snssdk.com", request.url.host)
            assertEquals(preloadPath, request.url.encodedPath)
            val expected = mapOf("version_code" to "74532", "version_name" to "7.4.5.32",
                "update_version_code" to "74532", "manifest_version_code" to "74532", "os" to "android",
                "ssmix" to "a", "channel" to "update_64", "device_type" to "25053RT47C", "device_brand" to "Redmi",
                "os_version" to "16", "os_api" to "36", "ac" to "wifi", "language" to "zh", "host_abi" to "arm64-v8a",
                "iid" to "11", "device_id" to "22", "cdid" to "synthetic")
            expected.forEach { (key, value) -> assertEquals(key, value, request.url.queryParameter(key)) }
            val bytes = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
            assertArrayEquals(signedBytes, bytes)
            assertEquals("gzip", request.header("Content-Encoding"))
            assertEquals(digest("MD5", bytes).hex().uppercase(), request.header("x-ss-stub"))
            val json = JSONObject(GZIPInputStream(bytes.inputStream()).reader().readText())
            assertEquals("preload", json.getString("dr_scene"))
            assertEquals(2, json.getJSONObject("biz_param").getInt("device_level"))
            assertEquals("123", json.getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0))
            assertEquals("fixture", request.header("x-medusa"))
            assertNull(request.header("Cookie")); assertNull(request.header("Cookie2"))
            200 to envelope()
        }
        assertEquals(1, MobilePlayback.ladder("123", device, http, signing, 1800000000000L).renditions.size)
        assertEquals(1, requests)
    }

    @Test fun emptyPrimaryUsesBodylessVideoTabWithTheSameUpdatedProfile() {
        val requests = mutableListOf<Request>()
        val http = client { request ->
            requests += request
            if (requests.size == 1) 200 to "" else {
                assertEquals("GET", request.method)
                assertEquals("api5-normal-sinfonlineb.fqnovel.com", request.url.host)
                assertEquals("74532", request.url.queryParameter("version_code"))
                assertEquals("1", request.url.queryParameter("device_level"))
                assertEquals("123", request.url.queryParameter("video_id"))
                assertNull(request.body)
                for (header in listOf("Content-Type", "Content-Encoding", "x-ss-stub", "Cookie", "Cookie2"))
                    assertNull(header, request.header(header))
                200 to envelope()
            }
        }
        assertEquals(1, MobilePlayback.ladder("123", device, http, unsigned, 1000).renditions.size)
        assertEquals(listOf("POST", "GET"), requests.map { it.method })
        assertEquals(listOf("22", "22"), requests.map { it.url.queryParameter("device_id") })
    }

    @Test fun incompatibleTabUsesOneAlternatePreloadWithSameIdentity() {
        val requests = mutableListOf<Request>()
        val http = client { request ->
            requests += request
            200 to when (requests.size) { 1 -> ""; 2 -> envelope(model("bytevc2")); 3 -> envelope(); else -> error("unbounded retry") }
        }
        assertEquals(1, MobilePlayback.ladder("123", device, http, unsigned, 1000).renditions.size)
        assertEquals(listOf("POST", "GET", "POST"), requests.map { it.method })
        assertEquals(listOf("reading.snssdk.com", "api5-normal-sinfonlineb.fqnovel.com", "api5-normal-sinfonlineb.fqnovel.com"), requests.map { it.url.host })
        assertEquals(preloadPath, requests.last().url.encodedPath)
        for (key in device.keys) assertEquals(listOf(device.getValue(key)), requests.map { it.url.queryParameter(key) }.distinct())
    }

    @Test fun appProvidedCodecNegotiationIsBoundedAndDoesNotForwardIdentityOrSignatures() {
        val requests = mutableListOf<Request>()
        val signing = MobilePlaybackSigner { url, _, _ -> MobileSignedRequest(url, mapOf("x-medusa" to "fixture")) }
        val endpoint = "https://vod.example.test/fplay?vid=vod-123&token=synthetic&force_fids=keep&codec_type=4"
        val http = client { request ->
            requests += request
            if (requests.size == 1) 200 to envelope(model("bytevc2", endpoint)) else {
                assertEquals("GET", request.method); assertNull(request.body)
                assertEquals("vod.example.test", request.url.host)
                assertEquals("vod-123", request.url.queryParameter("vid"))
                assertEquals("synthetic", request.url.queryParameter("token"))
                assertEquals("keep", request.url.queryParameter("force_fids"))
                for (key in listOf("x-medusa", "x-helios", "x-khronos", "x-ss-stub", "Cookie", "Cookie2")) assertNull(key, request.header(key))
                assertNull(request.url.queryParameter("device_id"))
                200 to vod(model(if (request.url.queryParameter("codec_type") == "1") "bytevc2" else "h265"))
            }
        }
        assertEquals("h265", MobilePlayback.ladder("123", device, http, signing, 1000).renditions.single().codec)
        assertEquals(listOf(null, "1", "3"), requests.map { it.url.queryParameter("codec_type") })
    }

    @Test fun rejectedWrongEpisodeOrMalformedPrimaryNeverAttemptsAlternateRoute() {
        for (payload in listOf(403 to "", 200 to "{", 200 to """{"code":110001}""", 200 to envelope(id = "999"))) {
            var count = 0
            val http = client { count++; payload }
            assertThrows(PlaybackSourceException::class.java) { MobilePlayback.model("123", device, http, unsigned, 1000) }
            assertEquals(1, count)
        }
    }

    @Test fun invalidVodResponseStopsWithoutTryingAnotherCodecOrRecoveringIdentity() {
        for (payload in listOf(403 to "", 200 to "{", 200 to """{"code":110001}""", 200 to " ".repeat(2 * 1024 * 1024 + 1))) {
            var count = 0
            val http = client { if (++count == 1) 200 to envelope(model("bytevc2", "https://vod.example.test/fplay?vid=123")) else payload }
            assertThrows(IOException::class.java) { MobilePlayback.model("123", device, http, unsigned, 1000) }
            assertEquals(2, count)
        }
    }

    @Test fun unusableVodStopsAfterTwoCodecs() {
        var count = 0
        val http = client { if (++count == 1) 200 to envelope(model("bytevc2", "https://vod.example.test/fplay?vid=123")) else 200 to vod(model("bytevc2")) }
        assertThrows(IncompatibleStreamException::class.java) { MobilePlayback.model("123", device, http, unsigned, 1000) }
        assertEquals(3, count)
    }

    @Test fun missingOrInvalidVodUrlIsNotMisclassifiedAsSkippableCodecFailure() {
        for (url in listOf(null, "", "javascript:invalid", "not-a-url", "https://", "https://user:password@example.test/video")) {
            var count = 0
            val malformed = model("bytevc2")
            val row = malformed.getJSONObject("video_list").getJSONObject("v")
            if (url == null) row.remove("main_url") else row.put("main_url", url)
            val http = client {
                if (++count == 1) 200 to envelope(model("bytevc2", "https://vod.example.test/fplay?vid=123"))
                else 200 to vod(malformed)
            }
            val error = assertThrows(PlaybackSourceException::class.java) {
                MobilePlayback.model("123", device, http, unsigned, 1000)
            }
            assertEquals(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE, error.reason)
            assertEquals(3, count)
        }
    }

    @Test fun incompatibleTabFollowedByEmptyAlternateDoesNotRotateIdentity() {
        var registrations = 0
        var models = 0
        val http = client { request ->
            if (request.url.encodedPath.contains("device_register")) {
                registrations++
                200 to """{"device_id":"22","install_id":"11"}"""
            } else {
                models++
                200 to if (request.method == "GET") envelope(model("bytevc2")) else ""
            }
        }
        val session = PlaybackSession(http, { 1000 }, unsigned)
        assertThrows(PlaybackSourceException::class.java) { session.model("123") }
        assertEquals(1, registrations)
        assertEquals(3, models)
    }

    @Test fun cancellationBetweenModelAndVodStopsFurtherRequests() {
        val scope = RequestScope()
        var count = 0
        val http = client {
            count++
            scope.cancel()
            200 to envelope(model("bytevc2", "https://vod.example.test/fplay?vid=123"))
        }
        assertThrows(IOException::class.java) {
            scope.run { MobilePlayback.model("123", device, http, unsigned, 1000) }
        }
        assertEquals(1, count)
    }

    @Test fun upstreamBackoffStopsBeforeGetFallback() {
        var count = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            count++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").header("Retry-After", "30").body("".toResponseBody()).build()
        }.build()
        val error = assertThrows(IOException::class.java) { MobilePlayback.model("123", device, http, unsigned, 1000) }
        assertFalse(error is EmptyPlaybackResponseException)
        assertEquals(1, count)
    }
}
