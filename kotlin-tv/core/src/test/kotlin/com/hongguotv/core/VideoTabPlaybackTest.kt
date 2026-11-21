// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VideoTabPlaybackTest {
    private val id = "123"
    private val media = "https://example.com/episode-123.mp4"
    private val device = mapOf("iid" to "11", "device_id" to "22", "cdid" to "test-anonymous")
    private val unsigned = MobilePlaybackSigner { url, _, _ -> MobileSignedRequest(url, emptyMap()) }

    // Independent synthetic vector from jackotom/hongguo-drama 92df757ed7be8ea658ee274a6a429b77f49e54e8,
    // src/native/playback-v5.js. The upstream V5 message field 13 was decoded and checked there.
    private val vectorValues = linkedMapOf(
        "aid" to "8662", "app_name" to "novelread", "version_code" to "74332", "version_name" to "7.4.3.32",
        "channel" to "vivo_8662_64", "device_platform" to "android", "device_type" to "PGT-AN10",
        "os_version" to "12", "update_version_code" to "74332", "iid" to "2234567890123456789",
        "cdid" to "synthetic-device", "device_id" to "1234567890123456789", "video_id" to "123",
        "video_id_type" to "0", "device_level" to "1", "need_all_video_definition" to "1",
        "need_mp4_align" to "0", "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
        "from_video_id" to "",
    )
    private val vectorBase = "https://example.test/reading/bookapi/video_tab/multi_video_model/v1/"
    private val vectorQuery = "aid=8662&app_name=novelread&version_code=74332&version_name=7.4.3.32" +
        "&channel=vivo_8662_64&device_platform=android&device_type=PGT-AN10&os_version=12&update_version_code=74332" +
        "&iid=2234567890123456789&cdid=synthetic-device&device_id=1234567890123456789&video_id=123&video_id_type=0" +
        "&device_level=1&need_all_video_definition=1&need_mp4_align=0&use_os_player=0&video_platform=1024" +
        "&source=0&from_video_id=&_rticket=1800000000018"

    private fun vectorSigner() = MobileV5Signer(
        clock = { 1800000000000L }, random = { 0.5019607841968536 },
        processNonce = "gICAgICAgICAgICAgICAgICA", mobileV5 = true,
    )

    @Test fun bodylessSigningSelectsTheIndependentEmptyMd5TicketAndDigest() {
        val signer = vectorSigner()
        assertEquals(0, signer.branch(vectorQuery, null, 1800000000))
        assertEquals("c152538c12c977ee2632a8f002bbb144f5d9c851", signer.hashF13(
            hex("ef59a05c75350b590dfff383153929d8803270ad92c129ac0e7a4a285ec9ba77"),
            hex("d41d8cd98f00b204e9800998ecf8427e"), 1800000000).hex())
        val signed = signer.signPlayback(vectorBase, vectorValues, null, 1800000000000L)
        assertEquals("1800000000018", signed.url.toHttpUrl().queryParameter("_rticket"))
        assertEquals("$vectorBase?$vectorQuery", signed.url)
        assertEquals(setOf("x-khronos", "x-helios", "x-medusa"), signed.headers.keys)
        assertNull(signed.body)
    }

    @Test fun bodylessMedusaEmbedsTheIndependentEmptyBodyDigest() {
        val medusa = vectorSigner().medusa("$vectorBase?$vectorQuery", null, 1800000000, vectorValues)
        // Hash of the independent upstream's complete base64 header, not of a locally derived expectation.
        assertEquals("95dfa35af2886f6a6683173b34bdf1eea36c6f0918e4ef3187fd9d3ec810135a",
            digest("SHA-256", medusa.toByteArray(Charsets.UTF_8)).hex())
    }

    private fun model(url: String = media) = JSONObject().put("video_list", JSONObject().put("720p",
        JSONObject().put("main_url", url).put("codec_type", "h264").put("width", 1280).put("height", 720)))

    private fun envelope(container: String = "video_model_data", episode: String = id, asString: Boolean = false): String {
        val video = model()
        val entries = JSONObject()
            .put("999", JSONObject().put("video_model", model("https://example.com/wrong-episode.mp4")))
            .put(episode, JSONObject().put("video_model", if (asString) video.toString() else video))
        return JSONObject().put("code", 0).put("data", JSONObject().put(container, entries)).toString()
    }

    private fun client(reply: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").body(body.toResponseBody()).build()
    }.build()

    @Test fun workingVideoTabRestoresPlaybackWhenThePostEndpointReturnsEmpty() {
        val requests = mutableListOf<Request>()
        val http = client { request ->
            requests += request
            if (request.method == "POST") 200 to "" else {
                assertEquals("api5-normal-sinfonlineb.fqnovel.com", request.url.host)
                assertEquals("/reading/bookapi/video_tab/multi_video_model/v1/", request.url.encodedPath)
                val expected = mapOf("aid" to "8662", "app_name" to "novelread", "version_code" to "74332",
                    "version_name" to "7.4.3.32", "channel" to "vivo_8662_64", "device_platform" to "android",
                    "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74332",
                    "iid" to "11", "device_id" to "22", "cdid" to "test-anonymous", "video_id" to "123",
                    "video_id_type" to "0", "device_level" to "1", "need_all_video_definition" to "1",
                    "need_mp4_align" to "0", "use_os_player" to "0", "video_platform" to "1024",
                    "source" to "0", "from_video_id" to "")
                expected.forEach { (name, value) -> assertEquals(name, value, request.url.queryParameter(name)) }
                assertNull(request.body)
                for (header in listOf("Content-Type", "Content-Encoding", "x-ss-stub", "Cookie", "Cookie2",
                    "x-argus", "x-ladon", "x-gorgon", "x-ss-req-ticket")) assertNull(header, request.header(header))
                for (header in listOf("x-khronos", "x-helios", "x-medusa")) {
                    assertFalse(header, request.header(header).isNullOrBlank())
                }
                assertEquals("1800000000", request.header("x-khronos"))
                assertEquals("application/json", request.header("Accept"))
                200 to envelope()
            }
        }
        // Exercise the production V5 signer, including its bodyless request path.
        val ladder = MobilePlayback.ladder(id, device, http, MobilePlayback.signer, 1800000000000L)
        assertEquals(media, ladder.select(720)!!.url)
        assertEquals(listOf("GET"), requests.map { it.method })
    }

    @Test fun anEmptyGetUsesOnePostFallbackWithTheSameIdentity() {
        val requests = mutableListOf<Request>()
        val http = client { request ->
            requests += request
            if (request.method == "GET") 200 to "" else 200 to envelope("1004")
        }
        val ladder = MobilePlayback.ladder(id, device, http, unsigned, 1800000000000L)
        assertEquals(media, ladder.select(720)!!.url)
        assertEquals(listOf("GET", "POST"), requests.map { it.method })
        assertEquals(listOf("22", "22"), requests.map { it.url.queryParameter("device_id") })
        assertEquals(listOf("11", "11"), requests.map { it.url.queryParameter("iid") })
        assertEquals("/novel/player/multi_video_model/player/v1", requests.last().url.encodedPath)
    }

    @Test fun bothResponseContainersSelectOnlyTheRequestedEpisodeForObjectAndStringModels() {
        for (container in listOf("video_model_data", "1004")) {
            for (asString in listOf(false, true)) {
                val http = client { 200 to envelope(container, asString = asString) }
                val ladder = MobilePlayback.ladder(id, device, http, unsigned, 1800000000000L)
                assertEquals("$container string=$asString", media, ladder.select(720)!!.url)
            }
        }
    }

    @Test fun rejectionMalformedDataAndWrongEpisodeDoNotTriggerPostFallback() {
        val cases = listOf(
            Triple(403, "", PlaybackSourceException.Reason.HTTP_REJECTED),
            Triple(200, """{"code":100103}""", PlaybackSourceException.Reason.REJECTED),
            Triple(200, "{", PlaybackSourceException.Reason.INVALID_RESPONSE),
            Triple(200, envelope(episode = "999"), PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE),
        )
        for ((code, body, reason) in cases) {
            val requests = mutableListOf<Request>()
            val http = client { request -> requests += request; code to body }
            val error = assertThrows(PlaybackSourceException::class.java) {
                MobilePlayback.model(id, device, http, unsigned, 1800000000000L)
            }
            assertEquals(reason, error.reason)
            assertEquals(listOf("GET"), requests.map { it.method })
        }
    }
}
