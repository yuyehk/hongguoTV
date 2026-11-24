// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackTransportRegressionTest {
    private val signer = MobilePlaybackSigner { url, _, _ -> MobileSignedRequest(url, emptyMap()) }
    private val device = mapOf("iid" to "11", "device_id" to "22", "cdid" to "test-anonymous")
    private fun entry(url: String = "https://example.com/video") = JSONObject()
        .put("main_url", url).put("codec_type", "h264").put("height", 720)
        .put("video_meta", JSONObject().put("vwidth", 1280).put("vheight", 720)
            .put("codec_type", "h264").put("definition", "720p"))
        .put("encrypt_info", JSONObject().put("encrypt", false))
    private fun model() = JSONObject().put("video_list", JSONArray().put(entry()))
    private fun responseModel(id: String = "123") = JSONObject().put("code", 0).put("data", JSONObject().put("1004", JSONObject().put(id, JSONObject().put("video_model", model())))).toString()
    private fun client(reply: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, text) = reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test").body(text.toResponseBody()).build()
    }.build()
    private fun registration(id: Int) = """{"device_id":"$id","install_id":"${id + 100}"}"""

    @Test fun gzipContractAndCompressedBodySignatureAreConsistent() {
        val http = client { request ->
            if (request.method == "GET") return@client 200 to ""
            assertEquals("/novel/player/multi_video_model/preload/v1", request.url.encodedPath)
            assertEquals("22", request.url.queryParameter("device_id"))
            assertEquals("gzip", request.header("Content-Encoding"))
            assertEquals("POST", request.method)
            val buffer = Buffer(); request.body!!.writeTo(buffer); val bytes = buffer.readByteArray()
            assertEquals(digest("MD5", bytes).hex().uppercase(), request.header("x-ss-stub"))
            val json = JSONObject(GZIPInputStream(bytes.inputStream()).reader().readText())
            assertEquals(2, json.getJSONObject("biz_param").getInt("device_level"))
            assertEquals("preload", json.getString("dr_scene"))
            assertEquals("123", json.getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0))
            200 to responseModel()
        }
        assertEquals(1, MobilePlayback.ladder("123", device, http, signer, 1000).renditions.size)
    }

    @Test fun neverUsesAnotherEpisodeOrUnidentifiedDirectModel() {
        for (text in listOf(responseModel("999"), JSONObject().put("data", JSONObject().put("video_model", model())).toString())) {
            assertThrows(PlaybackSourceException::class.java) { MobilePlayback.model("123", device, client { 200 to text }, signer, 1) }
        }
    }

    @Test fun acceptsExactEntryWithStringModelAndRejectsNonNumericIdBeforeRequest() {
        val text = JSONObject().put("data", JSONObject().put("123", JSONObject().put("video_model", model().toString()))).toString()
        assertEquals(1, MobilePlayback.ladder("123", device, client { 200 to text }, signer, 1).renditions.size)
        assertThrows(IllegalArgumentException::class.java) { MobilePlayback.model("123\"", device, client { error("must not request") }, signer, 1) }
    }

    @Test fun supportsArrayAndObjectPlainAndBase64UrlsAndIsolatesBadRows() {
        val encoded = "https://example.com/base64".toByteArray().base64()
        for (rows in listOf(
            JSONArray().put("garbage").put(entry("javascript:alert(1)")).put(entry(encoded)).put(entry()),
            JSONObject().put("bad", "garbage").put("720", entry(encoded)).put("1080", entry()),
        )) {
            assertEquals(2, PlaybackModels.renditions(JSONObject().put("video_list", rows)).size)
        }
        val brokenSeed = JSONObject().put("key_seed", "!").put("video_list", JSONArray().put(entry()))
        assertEquals(1, PlaybackModels.renditions(brokenSeed).size)
    }

    @Test fun incompatibleAndBrokenEncryptionRowsDoNotHideValidVariants() {
        val rows = JSONArray().put(entry().put("codec_type", "bytevc2"))
            .put(entry().put("video_meta", JSONObject().put("codec_type", "bvc2")))
            .put(entry().put("spade_a", "bad"))
            .put(entry().put("encrypt_info", JSONObject().put("encrypt", true)))
            .put(entry("https://user:password@example.com/private"))
            .put(entry())
        assertEquals(1, PlaybackModels.renditions(JSONObject().put("video_list", rows)).size)
    }

    @Test fun responseBoundRejectsOversizeAndMalformedJson() {
        for (text in listOf(" ".repeat(2 * 1024 * 1024 + 1), "not json")) {
            assertThrows(IOException::class.java) { MobilePlayback.model("123", device, client { 200 to text }, signer, 1) }
        }
    }

    @Test fun constructionIsLazyAndRegistrationIsPrivateAndReused() {
        val registers = AtomicInteger(); val requests = AtomicInteger()
        val original = VendorConstants.device.toMap()
        val http = client { request ->
            assertNull(request.header("Cookie"))
            if (request.url.encodedPath.contains("device_register")) 200 to registration(registers.incrementAndGet())
            else { requests.incrementAndGet(); 200 to responseModel() }
        }.newBuilder().cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> = error("account cookie jar must not be read")
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { error("account cookie jar must not be changed") }
        }).build()
        val session = PlaybackSession(http, { 1000 }, signer)
        assertEquals(0, registers.get())
        session.ladder("123"); session.ladder("123")
        assertEquals(1, registers.get()); assertEquals(2, requests.get())
        assertEquals(original, VendorConstants.device)
    }

    @Test fun emptyResponseRefreshesOnceAndThenStops() {
        val registers = AtomicInteger(); val requests = AtomicInteger()
        val session = PlaybackSession(client { request ->
            if (request.url.encodedPath.contains("device_register")) 200 to registration(registers.incrementAndGet())
            else { requests.incrementAndGet(); 200 to "" }
        }, { 1000 }, signer)
        assertThrows(EmptyPlaybackResponseException::class.java) { session.model("123") }
        assertEquals(2, registers.get()); assertEquals(4, requests.get())
        assertThrows(IOException::class.java) { session.model("123") }
        assertEquals(2, registers.get())
    }

    @Test fun httpRejectionBusinessRejectionAndWrongEpisodeNeverRefresh() {
        for (rejected in listOf(403 to "", 200 to """{"code":101000}""", 200 to responseModel("999"))) {
            val registers = AtomicInteger()
            val session = PlaybackSession(client { request ->
                if (request.url.encodedPath.contains("device_register")) 200 to registration(registers.incrementAndGet()) else rejected
            }, { 1000 }, signer)
            assertThrows(PlaybackSourceException::class.java) { session.model("123") }
            assertEquals(1, registers.get())
        }
    }

    @Test fun failedRegistrationCoolsDownAndCanRetryAfterThirtySeconds() {
        var now = 1000L; val registers = AtomicInteger()
        val session = PlaybackSession(client { registers.incrementAndGet(); 200 to "{}" }, { now }, signer)
        repeat(2) { assertThrows(IOException::class.java) { session.model("123") } }
        assertEquals(1, registers.get())
        now += 30_000
        assertThrows(IOException::class.java) { session.model("123") }
        assertEquals(2, registers.get())
    }

    @Test fun concurrentEmptyResponsesShareOneRecovery() {
        val count = 4; val oldRequests = CountDownLatch(count); val registers = AtomicInteger()
        val session = PlaybackSession(client { request ->
            if (request.url.encodedPath.contains("device_register")) 200 to registration(registers.incrementAndGet())
            else if (request.url.queryParameter("device_id") == "1") {
                oldRequests.countDown(); assertTrue(oldRequests.await(5, TimeUnit.SECONDS)); 200 to ""
            } else 200 to responseModel()
        }, { 1000 }, signer)
        val executor = Executors.newFixedThreadPool(count)
        try {
            val tasks = (1..count).map { executor.submit<StreamLadder> { session.ladder("123") } }
            tasks.forEach { assertEquals(1, it.get(10, TimeUnit.SECONDS).renditions.size) }
            assertEquals(2, registers.get())
        } finally { executor.shutdownNow() }
    }

    @Test fun cancellationDoesNotRegisterOrRecoverOrCoolDown() {
        val calls = AtomicInteger(); val scope = RequestScope()
        val session = PlaybackSession(client { calls.incrementAndGet(); 200 to registration(1) }, { 1000 }, signer)
        scope.cancel()
        assertThrows(IOException::class.java) { scope.run { session.model("123") } }
        assertEquals(0, calls.get())
    }

    @Test fun unknownLengthOversizeBodyIsBoundedToo() {
        val oversized = " ".repeat(2 * 1024 * 1024 + 1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer().writeUtf8(oversized)
            val body = object : okhttp3.ResponseBody() {
                override fun contentType(): okhttp3.MediaType? = null
                override fun contentLength() = -1L
                override fun source(): okio.BufferedSource = buffer
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test").body(body).build()
        }.build()
        assertThrows(IOException::class.java) { MobilePlayback.model("123", device, http, signer, 1) }
    }

    @Test fun failedRecoveryKeepsOldIdentityAndSharesCooldown() {
        var now = 1000L; val registers = AtomicInteger(); val deviceIds = mutableListOf<String?>()
        val session = PlaybackSession(client { request ->
            if (request.url.encodedPath.contains("device_register")) {
                val attempt = registers.incrementAndGet()
                200 to if (attempt == 2) "{}" else registration(attempt)
            } else { deviceIds += request.url.queryParameter("device_id"); 200 to "" }
        }, { now }, signer)
        assertThrows(IOException::class.java) { session.model("123") }
        assertThrows(IOException::class.java) { session.model("123") }
        assertEquals(2, registers.get())
        assertEquals(listOf("1", "1", "1", "1"), deviceIds)
        now += 30_000
        assertThrows(EmptyPlaybackResponseException::class.java) { session.model("123") }
        assertEquals(3, registers.get())
    }

    @Test fun cancellationDuringEmptyReadPropagatesWithoutIdentityRefresh() {
        val scope = RequestScope(); val registers = AtomicInteger()
        val session = PlaybackSession(client { request ->
            if (request.url.encodedPath.contains("device_register")) 200 to registration(registers.incrementAndGet())
            else { scope.cancel(); 200 to "" }
        }, { 1000 }, signer)
        assertThrows(IOException::class.java) { scope.run { session.model("123") } }
        assertEquals(1, registers.get())
    }

    @Test fun preloadEmptyResponseDoesNotRefreshOrConsumeForegroundRecoveryBudget() {
        val registers = AtomicInteger(); val requests = AtomicInteger()
        val session = PlaybackSession(client { request ->
            if (request.url.encodedPath.contains("device_register")) 200 to registration(registers.incrementAndGet())
            else {
                requests.incrementAndGet()
                200 to if (request.url.queryParameter("device_id") == "1") "" else responseModel()
            }
        }, { 1000 }, signer)
        assertThrows(EmptyPlaybackResponseException::class.java) { session.ladder("123", allowIdentityRecovery = false) }
        assertEquals(1, registers.get()); assertEquals(2, requests.get())
        assertEquals(1, session.ladder("123").renditions.size)
        assertEquals(2, registers.get()); assertEquals(5, requests.get())
    }

    @Test fun successfulRecoveryIsRateLimitedFromItsStartAndCanRecoverAfterThirtySeconds() {
        val registers = AtomicInteger(); val secondRequests = AtomicInteger()
        val now = java.util.concurrent.atomic.AtomicLong(1000)
        val session = PlaybackSession(client { request ->
            if (request.url.encodedPath.contains("device_register")) 200 to registration(registers.incrementAndGet())
            else 200 to when (request.url.queryParameter("device_id")) {
                "1" -> ""
                "2" -> if (secondRequests.incrementAndGet() == 1) responseModel() else ""
                else -> responseModel()
            }
        }, { now.get() }, signer)
        assertEquals(1, session.ladder("123").renditions.size)
        assertEquals(2, registers.get())
        assertThrows(IOException::class.java) { session.model("123") }
        assertEquals(2, registers.get())
        now.addAndGet(30_000)
        assertEquals(1, session.ladder("123").renditions.size)
        assertEquals(3, registers.get())
    }

    @Test fun emptyNewIdentityCannotStartAnotherRecoveryBeforeFirstRetryFinishes() {
        val registers = AtomicInteger(); val secondRequests = AtomicInteger()
        val retryEntered = CountDownLatch(1); val finishRetry = CountDownLatch(1)
        val session = PlaybackSession(client { request ->
            if (request.url.encodedPath.contains("device_register")) 200 to registration(registers.incrementAndGet())
            else {
                if (request.url.queryParameter("device_id") == "2" && secondRequests.incrementAndGet() == 1) {
                    retryEntered.countDown()
                    assertTrue(finishRetry.await(5, TimeUnit.SECONDS))
                }
                200 to ""
            }
        }, { 1000 }, signer)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<Throwable> { assertThrows(EmptyPlaybackResponseException::class.java) { session.model("123") } }
            assertTrue(retryEntered.await(5, TimeUnit.SECONDS))
            assertThrows(IOException::class.java) { session.model("123") }
            assertEquals("The published but unvalidated identity must not register a third device", 2, registers.get())
            finishRetry.countDown()
            assertTrue(first.get(10, TimeUnit.SECONDS) is EmptyPlaybackResponseException)
            assertEquals(2, registers.get())
        } finally { finishRetry.countDown(); executor.shutdownNow() }
    }

    @Test fun staleSecondEmptyResponseDoesNotCoolDownAnAlreadyReplacedIdentity() {
        val now = java.util.concurrent.atomic.AtomicLong(1000)
        val registers = AtomicInteger(); val secondRequests = AtomicInteger(); val thirdRequests = AtomicInteger()
        val oldRetryEntered = CountDownLatch(1); val finishOldRetry = CountDownLatch(1)
        val session = PlaybackSession(client { request ->
            if (request.url.encodedPath.contains("device_register")) 200 to registration(registers.incrementAndGet())
            else 200 to when (request.url.queryParameter("device_id")) {
                "1" -> ""
                "2" -> {
                    if (secondRequests.incrementAndGet() == 1) {
                        oldRetryEntered.countDown()
                        assertTrue(finishOldRetry.await(5, TimeUnit.SECONDS))
                    }
                    ""
                }
                "3" -> if (thirdRequests.incrementAndGet() == 1) responseModel() else ""
                else -> responseModel()
            }
        }, { now.get() }, signer)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val old = executor.submit<Throwable> { assertThrows(EmptyPlaybackResponseException::class.java) { session.model("123") } }
            assertTrue(oldRetryEntered.await(5, TimeUnit.SECONDS))
            now.addAndGet(30_000)
            assertEquals(1, session.ladder("123").renditions.size)
            assertEquals(3, registers.get())
            now.addAndGet(30_000)
            finishOldRetry.countDown()
            assertTrue(old.get(10, TimeUnit.SECONDS) is EmptyPlaybackResponseException)
            // The late identity-2 failure must not prevent identity 3's eligible recovery.
            assertEquals(1, session.ladder("123").renditions.size)
            assertEquals(4, registers.get())
        } finally { finishOldRetry.countDown(); executor.shutdownNow() }
    }

    @Test fun inlineAudioOrMissingEncryptionMetadataDoesNotBecomePlayableVideo() {
        val audio = entry("https://example.com/audio.mp4").put("codec_type", "aac")
        val missingCrypto = entry().also { it.remove("encrypt_info") }
        val missingMeta = entry().also { it.remove("video_meta") }
        for (row in listOf(audio, missingCrypto, missingMeta)) {
            val model = JSONObject().put("video_list", JSONArray().put(row))
                .put("fallback_api", "https://example.com/fallback")
            assertTrue(PlaybackModels.renditions(model).isEmpty())
            assertEquals("https://example.com/fallback", PlaybackModels.fallback(model))
        }
        assertEquals(1, PlaybackModels.renditions(JSONObject().put("video_list", JSONArray()
            .put(audio).put(missingCrypto).put(missingMeta).put(entry()))).size)
    }
}
