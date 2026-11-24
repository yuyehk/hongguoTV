// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobileSdkPlaybackSessionTest {
    private val first = "7707465250475346969"
    private val second = "7707465250475363353"
    private val registerPath = "/service/2/device_register/"
    private val bootstrapPath = "/sdi/get_token"
    private val playerPath = "/novel/player/multi_video_model/player/v1"
    private val registration = """{"device_id":"1234567890123","install_id":"2345678901234"}"""
    private val deviceId = "1234567890123"
    private val installId = "2345678901234"
    private val expectedCookie = "install_id=$installId; odin_tt=anonymous-device-cookie; ttreq=anonymous-request-cookie; store-region=cn; store-region-src=did"
    private class Reply(val code: Int = 200, val text: String = "", val headers: List<Pair<String, String>> = emptyList())
    private fun client(reply: (Request) -> Reply): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val r = reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(r.code).message("test")
            .apply { r.headers.forEach { (name, value) -> addHeader(name, value) } }.body(r.text.toResponseBody()).build()
    }.build()
    private fun registered(extra: List<Pair<String, String>> = emptyList()) = Reply(text = registration, headers = listOf(
        "Set-Cookie" to "odin_tt=anonymous-device-cookie; Domain=.fqnovel.com; Secure",
        "Set-Cookie" to "ttreq=anonymous-request-cookie; Secure",
        "Set-Cookie" to "store-region=cn; Path=/",
        "Set-Cookie" to "store-region-src=did; Path=/",
        "Set-Cookie" to "sessionid=must-never-forward; Path=/",
        "Set-Cookie" to "install_id=not-the-registered-installation; Path=/",
    ) + extra)
    private fun model(id: String) = JSONObject().put("code", 0).put("data", JSONObject().put("1004", JSONObject()
        .put(id, JSONObject().put("video_model", JSONObject().put("duration", 158.918).put("marker", id)
            .put("fallback_api", "https://example.com/authorized-vod"))))).toString()
    private val decode: (ByteArray) -> MobileSdkExchangeResult = { bytes ->
        val status = bytes.toString(Charsets.UTF_8).toInt()
        MobileSdkExchangeResult(status, if (status == 202) "temporary-test-token" else "ready-test-token")
    }
    private open class Factory : MobileSdkRequestFactory {
        class Call(val type: String, val id: String?, val device: Map<String, String>, val cookies: Map<String, String>, val token: String?, val previous: Long)
        val calls = Collections.synchronizedList(mutableListOf<Call>())
        override fun bootstrap(device: Map<String, String>, cookies: Map<String, String>, token: String?, previousLadon: Long): MobileSdkRequest {
            calls += Call("bootstrap", null, device, cookies, token, previousLadon)
            return MobileSdkRequest("https://mssdk.zijieapi.com/sdi/get_token", emptyMap(), byteArrayOf(1), previousLadon + 1)
        }
        override fun playback(id: String, device: Map<String, String>, cookies: Map<String, String>, token: String, previousLadon: Long): MobileSdkRequest {
            calls += Call("player", id, device, cookies, token, previousLadon)
            return MobileSdkRequest("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/player/v1?test_id=$id", emptyMap(), byteArrayOf(2), previousLadon + 1)
        }
    }

    @Test fun registrationMatchesSdkProfileAndEncryptionWithoutAccountData() {
        val now = 1795520000123L
        val prepared = MobileSdkRegistration.prepare(now)
        val request = prepared.request.build()
        assertEquals("log.snssdk.com", request.url.host)
        assertEquals(registerPath, request.url.encodedPath)
        assertEquals("23127PN0CC", request.url.queryParameter("device_type"))
        assertEquals("xiaomi_8662_64", request.url.queryParameter("channel"))
        assertEquals("73932", request.url.queryParameter("version_code"))
        assertEquals("1200*2670", request.url.queryParameter("resolution"))
        assertEquals((now / 1000).toString(), request.url.queryParameter("ts"))
        assertNull(request.url.queryParameter("device_id")); assertNull(request.header("Cookie"))
        val buffer = Buffer(); request.body!!.writeTo(buffer); val bytes = buffer.readByteArray()
        assertEquals(digest("MD5", bytes).hex().uppercase(), request.header("X-SS-STUB"))
        val root = JSONObject(MobileSdkRegistration.responseText(bytes))
        assertEquals(now, root.getLong("_gen_time"))
        val header = root.getJSONObject("header")
        assertEquals(prepared.device.getValue("cdid"), header.getString("cdid"))
        assertEquals(prepared.device.getValue("openudid"), header.getString("openudid"))
        assertEquals("Xiaomi 14", header.getString("device_model"))
        assertEquals("14", header.getString("os_version"))
        assertEquals(34, header.getInt("os_api"))
        assertEquals("2670x1200", header.getString("resolution"))
        assertEquals("3.7.3-rc.116-douyin", header.getString("sdk_version"))
        assertFalse(header.has("device_id")); assertFalse(header.has("iid"))
    }

    @Test fun twoStepBootstrapReusesPrivateIdentityCookiesAndTokenAcrossEpisodes() {
        val factory = Factory(); val registrations = AtomicInteger(); val bootstrap = AtomicInteger()
        val originalDevice = VendorConstants.device.toMap()
        val http = client { request ->
            assertNull(request.header("Authorization")); assertNull(request.header("X-TT-Token"))
            when (request.url.encodedPath) {
                registerPath -> { registrations.incrementAndGet(); assertNull(request.header("Cookie")); registered() }
                bootstrapPath -> { assertEquals(expectedCookie, request.header("Cookie")); Reply(text = if (bootstrap.incrementAndGet() == 1) "202" else "200") }
                else -> { assertEquals(expectedCookie, request.header("Cookie")); Reply(text = model(request.url.queryParameter("test_id")!!)) }
            }
        }.newBuilder().cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> = error("must not load account cookies")
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = error("must not modify account cookies")
        }).build()
        val session = MobileSdkPlaybackSession(http, { 1000 }, factory, decode)
        assertEquals(0, registrations.get())
        assertEquals(first, session.model(first).getString("marker"))
        assertEquals(second, session.model(second).getString("marker"))
        assertEquals(1, registrations.get()); assertEquals(2, bootstrap.get())
        assertEquals(listOf(0L, 1L, 2L, 3L), factory.calls.map { it.previous })
        assertEquals(listOf(null, "temporary-test-token", "ready-test-token", "ready-test-token"), factory.calls.map { it.token })
        assertTrue(factory.calls.all { it.device["device_id"] == deviceId && it.device["iid"] == installId })
        assertTrue(factory.calls.all { it.cookies.keys == setOf("odin_tt", "ttreq", "store-region", "store-region-src") })
        assertEquals(originalDevice, VendorConstants.device)
    }

    @Test fun actualSignerCookieContractMatchesSessionTransport() {
        val sends = AtomicInteger()
        val http = client { request ->
            if (request.url.encodedPath == registerPath) registered() else {
                sends.incrementAndGet()
                assertEquals(expectedCookie, request.header("Cookie"))
                assertFalse(request.headers.names().any { it.equals("X-TT-Token", true) })
                assertNotNull(request.header("X-Medusa"))
                if (request.url.encodedPath == bootstrapPath) Reply(text = "200") else Reply(text = model(first))
            }
        }
        val clock = { 1795520000000L }
        val session = MobileSdkPlaybackSession(http, clock, MobileSdkSigner(clock), decode)
        assertEquals(first, session.model(first).getString("marker"))
        assertEquals(2, sends.get())
    }

    @Test fun repeatedContinuationStopsAfterTwoBootstrapRequests() {
        val sends = AtomicInteger(); val factory = Factory()
        val session = MobileSdkPlaybackSession(client { request ->
            if (request.url.encodedPath == registerPath) registered() else { sends.incrementAndGet(); Reply(text = "202") }
        }, { 1000 }, factory, decode)
        val failure = assertThrows(PlaybackSourceException::class.java) { session.model(first) }
        assertEquals(202, failure.upstreamCode); assertEquals(2, sends.get())
        assertEquals(2, factory.calls.size)
        assertThrows(IOException::class.java) { session.model(second) }
        assertEquals(2, sends.get())
    }

    @Test fun httpBusinessEmptyAndWrongEpisodeNeverRetryAndLaterRefreshKeepsDevice() {
        for (reply in listOf(Reply(403), Reply(429), Reply(text = "{\"code\":101000}"), Reply(text = ""), Reply(text = model(second)))) {
            var now = 1000L
            val registrations = AtomicInteger(); val bootstraps = AtomicInteger(); val players = AtomicInteger()
            val session = MobileSdkPlaybackSession(client { request ->
                when (request.url.encodedPath) {
                    registerPath -> { registrations.incrementAndGet(); registered() }
                    bootstrapPath -> { bootstraps.incrementAndGet(); Reply(text = "200") }
                    else -> { players.incrementAndGet(); reply }
                }
            }, { now }, Factory(), decode)
            assertThrows(PlaybackSourceException::class.java) { session.model(first) }
            assertEquals(1, players.get()); assertEquals(1, bootstraps.get()); assertEquals(1, registrations.get())
            now += 1000
            assertThrows(IOException::class.java) { session.model(first) }
            assertEquals(1, players.get())
            now = 31_000
            assertThrows(PlaybackSourceException::class.java) { session.model(first) }
            assertEquals(2, players.get()); assertEquals(2, bootstraps.get()); assertEquals(1, registrations.get())
        }
    }

    @Test fun retryAfterAndRegistrationRejectionStopBeforeBootstrap() {
        for (reply in listOf(Reply(403), Reply(429), Reply(text = registration, headers = listOf("Retry-After" to "30")), registered(listOf("Set-Cookie" to "store-region-src=uid")))) {
            val count = AtomicInteger()
            val session = MobileSdkPlaybackSession(client { count.incrementAndGet(); reply }, { 1000 }, Factory(), decode)
            assertThrows(IOException::class.java) { session.model(first) }
            assertThrows(IOException::class.java) { session.model(second) }
            assertEquals(1, count.get())
        }
    }

    @Test fun cancelledResponseDoesNotRetryAndNewIntentReusesAnonymousDevice() {
        val scope = RequestScope(); val registrations = AtomicInteger(); val players = AtomicInteger(); val bootstraps = AtomicInteger()
        val session = MobileSdkPlaybackSession(client { request ->
            when (request.url.encodedPath) {
                registerPath -> { registrations.incrementAndGet(); registered() }
                bootstrapPath -> { bootstraps.incrementAndGet(); Reply(text = "200") }
                else -> { if (players.incrementAndGet() == 1) scope.cancel(); Reply(text = model(first)) }
            }
        }, { 1000 }, Factory(), decode)
        assertThrows(IOException::class.java) { scope.run { session.model(first) } }
        assertEquals(1, players.get()); assertEquals(1, registrations.get())
        assertEquals(first, session.model(first).getString("marker"))
        assertEquals(2, players.get()); assertEquals(2, bootstraps.get()); assertEquals(1, registrations.get())
    }

    @Test fun concurrentPreloadsSerializeStateAndCancelledWaiterMakesNoRequest() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val waiting = CountDownLatch(1)
        val factory = Factory(); val registrations = AtomicInteger(); val players = AtomicInteger()
        val session = MobileSdkPlaybackSession(client { request ->
            when (request.url.encodedPath) {
                registerPath -> { registrations.incrementAndGet(); registered() }
                bootstrapPath -> Reply(text = "200")
                else -> {
                    if (players.incrementAndGet() == 1) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
                    Reply(text = model(request.url.queryParameter("test_id")!!))
                }
            }
        }, { 1000 }, factory, decode)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val a = executor.submit<JSONObject> { session.model(first) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val cancelled = RequestScope()
            val b = executor.submit<Throwable> { cancelled.run { waiting.countDown(); assertThrows(IOException::class.java) { session.model(second) } } }
            assertTrue(waiting.await(5, TimeUnit.SECONDS)); cancelled.cancel()
            assertTrue(b.get(3, TimeUnit.SECONDS) is IOException)
            val c = executor.submit<JSONObject> { session.model(second) }
            release.countDown()
            assertEquals(first, a.get(5, TimeUnit.SECONDS).getString("marker"))
            assertEquals(second, c.get(5, TimeUnit.SECONDS).getString("marker"))
            assertEquals(1, registrations.get()); assertEquals(2, players.get())
            assertEquals(listOf(0L, 1L, 2L), factory.calls.map { it.previous })
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun invalidIdAndCancelledIntentCannotRegister() {
        val count = AtomicInteger(); val session = MobileSdkPlaybackSession(client { count.incrementAndGet(); registered() }, { 1000 }, Factory(), decode)
        assertThrows(IllegalArgumentException::class.java) { session.model("invalid") }
        val scope = RequestScope(); scope.cancel()
        assertThrows(IOException::class.java) { scope.run { session.model(first) } }
        assertEquals(0, count.get())
    }

    @Test fun preloadCannotInitializeFreshSessionOrConsumeForegroundRecovery() {
        val registrations = AtomicInteger(); val bootstraps = AtomicInteger()
        val session = MobileSdkPlaybackSession(client { request ->
            when (request.url.encodedPath) {
                registerPath -> { registrations.incrementAndGet(); registered() }
                bootstrapPath -> { bootstraps.incrementAndGet(); Reply(text = "200") }
                else -> Reply(text = model(request.url.queryParameter("test_id")!!))
            }
        }, { 1000 }, Factory(), decode)
        assertThrows(IOException::class.java) { session.model(second, allowSessionRecovery = false) }
        assertEquals(0, registrations.get()); assertEquals(0, bootstraps.get())
        assertEquals(first, session.model(first).getString("marker"))
        assertEquals(second, session.model(second, allowSessionRecovery = false).getString("marker"))
        assertEquals(1, registrations.get()); assertEquals(1, bootstraps.get())
    }

    @Test fun preloadCannotRestoreCancelledOrFailedSessionAndDoesNotExtendCooldown() {
        for (cancel in listOf(true, false)) {
            var now = 1000L
            val scope = RequestScope(); val registrations = AtomicInteger(); val bootstraps = AtomicInteger(); val players = AtomicInteger()
            val factory = Factory()
            val session = MobileSdkPlaybackSession(client { request ->
                when (request.url.encodedPath) {
                    registerPath -> { registrations.incrementAndGet(); registered() }
                    bootstrapPath -> { bootstraps.incrementAndGet(); Reply(text = "200") }
                    else -> {
                        if (players.incrementAndGet() == 2) {
                            if (cancel) scope.cancel()
                            Reply(403)
                        } else Reply(text = model(request.url.queryParameter("test_id")!!))
                    }
                }
            }, { now }, factory, decode)
            assertEquals(first, session.model(first).getString("marker"))
            assertThrows(IOException::class.java) { scope.run { session.model(first) } }
            now += 20_000
            assertThrows(IOException::class.java) { session.model(second, allowSessionRecovery = false) }
            assertEquals(1, registrations.get()); assertEquals(1, bootstraps.get()); assertEquals(2, players.get())
            // A failed request retains the original 30s cooldown; a cancelled one has none.
            if (!cancel) now = 31_000
            assertEquals(second, session.model(second, allowSessionRecovery = true).getString("marker"))
            assertEquals(1, registrations.get()); assertEquals(2, bootstraps.get()); assertEquals(3, players.get())
            assertTrue(factory.calls.all { it.device["device_id"] == deviceId })
        }
    }

    @Test fun rejectsRedirectedFactoryAndAccountHeadersBeforeTransport() {
        for (malformed in listOf(
            MobileSdkRequest("https://unrelated.example/sdi/get_token", emptyMap(), byteArrayOf(1), 1),
            MobileSdkRequest("https://mssdk.zijieapi.com/sdi/get_token", mapOf("Cookie" to "sessionid=account"), byteArrayOf(1), 1),
            MobileSdkRequest("https://mssdk.zijieapi.com/sdi/get_token", mapOf("X-TT-Token" to "account"), byteArrayOf(1), 1),
        )) {
            val count = AtomicInteger()
            val factory = object : Factory() {
                override fun bootstrap(device: Map<String, String>, cookies: Map<String, String>, token: String?, previousLadon: Long) = malformed
            }
            val session = MobileSdkPlaybackSession(client { count.incrementAndGet(); registered() }, { 1000 }, factory, decode)
            assertThrows(PlaybackSourceException::class.java) { session.model(first) }
            assertEquals(1, count.get())
        }
    }

    @Test fun oversizedBootstrapAndPlayerResponsesAreBounded() {
        for (stage in listOf(bootstrapPath, playerPath)) {
            val calls = AtomicInteger()
            val session = MobileSdkPlaybackSession(client { request ->
                calls.incrementAndGet()
                when (request.url.encodedPath) {
                    registerPath -> registered()
                    stage -> Reply(text = "x".repeat(if (stage == bootstrapPath) 128 * 1024 + 1 else 2 * 1024 * 1024 + 1))
                    else -> Reply(text = "200")
                }
            }, { 1000 }, Factory(), decode)
            assertThrows(IOException::class.java) { session.model(first) }
            assertEquals(if (stage == bootstrapPath) 2 else 3, calls.get())
        }
    }
}
