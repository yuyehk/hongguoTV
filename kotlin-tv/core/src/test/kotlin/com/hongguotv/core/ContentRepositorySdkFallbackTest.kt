package com.hongguotv.core

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

/** Real repository/signing/exchange/session chain, with every HTTP call terminated in memory. */
class ContentRepositorySdkFallbackTest {
    @Test fun repositoryConstructionStartsNeitherAnonymousProtocol() {
        val transport = Transport()
        ContentRepository(transport.http) { NOW }
        assertTrue(transport.events.isEmpty())
        assertEquals(0, transport.accountCookieAccesses)
    }

    @Test fun boundedLegacyEmptyRecoverySelectsSdkAndReusesItForNextEpisodeAndPreload() {
        val transport = Transport()
        val repo = ContentRepository(transport.http) { NOW }
        assertEquals(media(E1), repo.streamLadder(E1).renditions.single().url)
        assertEquals(listOf(
            "register:legacy", "legacy:preload", "legacy:tab",
            "register:legacy", "legacy:preload", "legacy:tab",
            "register:sdk", "sdk:exchange", "sdk:exchange", "sdk:playback",
        ), transport.events)
        assertEquals(media(E2), repo.streamLadder(E2).renditions.single().url)
        assertEquals(media(E3), repo.streamLadder(E3, allowIdentityRecovery = false).renditions.single().url)
        assertEquals(4, transport.legacyModels)
        assertEquals(2, transport.legacyRegisters)
        assertEquals(1, transport.sdkRegisters)
        assertEquals(2, transport.exchanges)
        assertEquals(listOf(E1, E2, E3), transport.sdkEpisodes)
        assertEquals(1, transport.sdkInstallIds.distinct().size)
        assertEquals(0, transport.accountCookieAccesses)
    }

    @Test fun httpBusinessMalformedAndUnrelatedEpisodeResponsesNeverStartSdk() {
        val failures = listOf(
            Reply(403, byteArrayOf()) to PlaybackSourceException.Reason.HTTP_REJECTED,
            Reply(429, byteArrayOf()) to PlaybackSourceException.Reason.HTTP_REJECTED,
            text("""{"code":101000,"message":"denied"}""") to PlaybackSourceException.Reason.REJECTED,
            text("not-json") to PlaybackSourceException.Reason.INVALID_RESPONSE,
            text(responseModel(E2)) to PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE,
        )
        for ((reply, reason) in failures) {
            val transport = Transport(legacy = { reply })
            val repo = ContentRepository(transport.http) { NOW }
            val failure = assertThrows(PlaybackSourceException::class.java) { repo.streamLadder(E1) }
            assertEquals(reason, failure.reason)
            assertEquals(listOf("register:legacy", "legacy:preload"), transport.events)
            assertEquals(0, transport.sdkRegisters)
            assertEquals(0, transport.exchanges)
            assertTrue(transport.sdkEpisodes.isEmpty())
        }
    }

    @Test fun unselectedSdkCannotBeStartedByPreloadAndForegroundRetainsItsRecoveryBudget() {
        val transport = Transport()
        val repo = ContentRepository(transport.http) { NOW }
        assertThrows(EmptyPlaybackResponseException::class.java) {
            repo.streamLadder(E1, allowIdentityRecovery = false)
        }
        assertEquals(listOf("register:legacy", "legacy:preload", "legacy:tab"), transport.events)
        assertEquals(0, transport.sdkRegisters)
        assertEquals(0, transport.exchanges)
        assertTrue(transport.sdkEpisodes.isEmpty())

        // The same frozen clock proves speculative failure did not consume the
        // foreground identity refresh or install a 30-second recovery cooldown.
        assertEquals(media(E1), repo.streamLadder(E1).renditions.single().url)
        assertEquals(2, transport.legacyRegisters)
        assertEquals(6, transport.legacyModels)
        assertEquals(1, transport.sdkRegisters)
        assertEquals(2, transport.exchanges)
        assertEquals(listOf(E1), transport.sdkEpisodes)
    }

    @Test fun workingLegacyModelsKeepExistingPlaybackAndPreloadWithoutSdkInitialization() {
        val transport = Transport(legacy = { request -> text(responseModel(episodeId(request))) })
        val repo = ContentRepository(transport.http) { NOW }
        assertEquals(media(E1), repo.streamLadder(E1).renditions.single().url)
        assertEquals(media(E2), repo.streamLadder(E2, allowIdentityRecovery = false).renditions.single().url)
        assertEquals(1, transport.legacyRegisters)
        assertEquals(2, transport.legacyModels)
        assertEquals(0, transport.sdkRegisters)
        assertEquals(0, transport.exchanges)
        assertTrue(transport.sdkEpisodes.isEmpty())
    }

    @Test fun incompatibleFirstSdkEpisodeKeepsSdkRouteForTheNextEpisode() {
        val codecRequests = mutableListOf<String>()
        val unsupportedRow = JSONObject().put("main_url", media(E1)).put("codec_type", "bytevc2")
            .put("video_meta", JSONObject().put("vwidth", 1280).put("vheight", 720).put("codec_type", "bytevc2"))
            .put("encrypt_info", JSONObject().put("encrypt", false))
        val firstModel = JSONObject().put("video_list", JSONArray().put(unsupportedRow))
            .put("fallback_api", "https://vod.example.test/sdk-vod?episode=$E1&codec_type=4")
        val transport = Transport(
            sdk = { id ->
                if (id == E1) text(JSONObject().put("code", 0).put("data", JSONObject().put("1004", JSONObject()
                    .put(E1, JSONObject().put("video_model", firstModel)))).toString())
                else text(responseModel(id))
            },
            vod = { request ->
                assertEquals("vod.example.test", request.url.host)
                assertEquals(E1, request.url.queryParameter("episode"))
                assertNull(request.header("Cookie"))
                codecRequests += request.url.queryParameter("codec_type")!!
                text(JSONObject().put("video_info", JSONObject().put("data", JSONObject()
                    .put("video_list", JSONObject().put("720p", unsupportedRow)))).toString())
            },
        )
        val repo = ContentRepository(transport.http) { NOW }
        assertThrows(IncompatibleStreamException::class.java) { repo.streamLadder(E1) }
        assertEquals(listOf("1", "3"), codecRequests)
        assertEquals(media(E2), repo.streamLadder(E2).renditions.single().url)
        assertEquals("skip must not return to the cooled-down legacy recovery", 4, transport.legacyModels)
        assertEquals(2, transport.legacyRegisters)
        assertEquals(1, transport.sdkRegisters)
        assertEquals(2, transport.exchanges)
        assertEquals(listOf(E1, E2), transport.sdkEpisodes)
        assertEquals(0, transport.accountCookieAccesses)
    }

    private class Transport(
        private val legacy: (Request) -> Reply = { Reply(200, byteArrayOf()) },
        private val sdk: (String) -> Reply = { text(responseModel(it)) },
        private val vod: ((Request) -> Reply)? = null,
    ) {
        val events = mutableListOf<String>()
        val sdkEpisodes = mutableListOf<String>()
        val sdkInstallIds = mutableListOf<String>()
        var legacyRegisters = 0
        var legacyModels = 0
        var sdkRegisters = 0
        var exchanges = 0
        var accountCookieAccesses = 0
        private val fixture = JSONObject(javaClass.getResource("/mobile-sdk-repository-exchange.json")!!.readText())
        val http: OkHttpClient = OkHttpClient.Builder().cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                accountCookieAccesses++
                throw AssertionError("Anonymous playback read the account cookie jar")
            }
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                accountCookieAccesses++
                throw AssertionError("Anonymous playback changed the account cookie jar")
            }
        }).addInterceptor { chain ->
            val request = chain.request()
            val reply = reply(request)
            // This interceptor never calls proceed: unexpected routes fail here,
            // including any accidental SDK-library, media, or external-service fetch.
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(reply.code).message("offline fixture").apply {
                    reply.headers.forEach { (name, value) -> addHeader(name, value) }
                }.body(reply.body.toResponseBody()).build()
        }.build()

        private fun reply(request: Request): Reply {
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie2"))
            return when (request.url.encodedPath) {
                "/service/2/device_register/" -> {
                    assertNull(request.header("Cookie"))
                    val sdk = request.url.queryParameter("channel") == "xiaomi_8662_64"
                    assertEquals(if (sdk) "log.snssdk.com" else "log5-applog-lf.fqnovel.com", request.url.host)
                    if (sdk) { sdkRegisters++; events += "register:sdk" }
                    else { legacyRegisters++; events += "register:legacy" }
                    val sequence = legacyRegisters + sdkRegisters
                    Reply(200, """{"code":0,"device_id":"${80000000 + sequence}","install_id":"${90000000 + sequence}"}""".toByteArray(), listOf(
                        "Set-Cookie" to "odin_tt=synthetic-device-cookie; Path=/",
                        "Set-Cookie" to "store-region=cn; Path=/",
                        "Set-Cookie" to "store-region-src=did; Path=/",
                        "Set-Cookie" to "sessionid=synthetic-account-cookie-must-be-discarded; Path=/",
                    ))
                }
                "/novel/player/multi_video_model/preload/v1" -> {
                    assertEquals("reading.snssdk.com", request.url.host)
                    assertNull(request.header("Cookie"))
                    legacyModels++; events += "legacy:preload"
                    legacy(request)
                }
                "/reading/bookapi/video_tab/multi_video_model/v1/" -> {
                    assertEquals("api5-normal-sinfonlineb.fqnovel.com", request.url.host)
                    assertNull(request.header("Cookie"))
                    legacyModels++; events += "legacy:tab"
                    legacy(request)
                }
                "/sdi/get_token" -> {
                    assertEquals("mssdk.zijieapi.com", request.url.host)
                    assertAnonymousSdkHeaders(request)
                    events += "sdk:exchange"
                    val rows = fixture.getJSONArray("responses")
                    assertTrue("SDK bootstrap exceeded its two-exchange bound", exchanges < rows.length())
                    Reply(200, hex(rows.getJSONObject(exchanges++).getString("packet")))
                }
                "/novel/player/multi_video_model/player/v1" -> {
                    assertEquals("api5-normal-sinfonlineb.fqnovel.com", request.url.host)
                    assertAnonymousSdkHeaders(request)
                    val id = episodeId(request)
                    assertEquals(4, bodyJson(request).getJSONObject("biz_param").getInt("device_level"))
                    sdkEpisodes += id
                    events += "sdk:playback"
                    sdk(id)
                }
                "/sdk-vod" -> vod?.invoke(request) ?: throw AssertionError("Unexpected SDK VOD route")
                else -> throw AssertionError("Unexpected offline route: ${request.url.host}${request.url.encodedPath}")
            }
        }

        private fun assertAnonymousSdkHeaders(request: Request) {
            assertEquals("POST", request.method)
            assertFalse(request.header("X-Medusa").isNullOrBlank())
            assertFalse(request.header("X-Gorgon").isNullOrBlank())
            val cookie = request.header("Cookie") ?: throw AssertionError("Missing own anonymous cookie")
            assertTrue(cookie.contains("odin_tt=synthetic-device-cookie"))
            assertTrue(cookie.endsWith("store-region-src=did"))
            assertFalse(cookie.contains("sessionid"))
            assertFalse(cookie.contains("synthetic-account-cookie"))
            val iid = request.url.queryParameter("iid") ?: throw AssertionError("Missing anonymous iid")
            assertTrue(cookie.startsWith("install_id=$iid;"))
            sdkInstallIds += iid
        }
    }

    private data class Reply(val code: Int, val body: ByteArray, val headers: List<Pair<String, String>> = emptyList())

    companion object {
        private const val NOW = 1_800_000_000_000L
        private const val E1 = "11111111"
        private const val E2 = "22222222"
        private const val E3 = "33333333"
        private fun media(id: String) = "https://media.example.test/episode-$id.mp4"
        private fun text(body: String) = Reply(200, body.toByteArray())
        private fun bodyJson(request: Request): JSONObject {
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val compressed = buffer.readByteArray()
            assertEquals("gzip", request.header("Content-Encoding"))
            assertEquals(digest("MD5", compressed).hex().uppercase(), request.header("X-SS-STUB"))
            return JSONObject(GZIPInputStream(compressed.inputStream()).reader().use { it.readText() })
        }
        private fun episodeId(request: Request): String = request.url.queryParameter("video_id")
            ?: bodyJson(request).getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0)
        private fun responseModel(id: String): String {
            val row = JSONObject().put("main_url", media(id)).put("codec_type", "h264")
                .put("video_meta", JSONObject().put("vwidth", 1280).put("vheight", 720)
                    .put("codec_type", "h264").put("definition", "720p"))
                .put("encrypt_info", JSONObject().put("encrypt", false))
            val model = JSONObject().put("video_list", JSONArray().put(row))
            return JSONObject().put("code", 0).put("data", JSONObject().put("1004", JSONObject()
                .put(id, JSONObject().put("video_model", model)))).toString()
        }
    }
}
