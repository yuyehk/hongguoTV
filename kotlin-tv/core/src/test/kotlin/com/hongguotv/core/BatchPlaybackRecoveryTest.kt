package com.hongguotv.core

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BatchPlaybackRecoveryTest {
    private val oldPath = "/novel/player/multi_video_model/preload/v1/"
    private val batchPath = "/novel/player/multi_video_model/v1/"
    private val registerPath = "/service/2/device_register/"
    private val registered = """{"device_id_str":"1345678901234567890","install_id_str":"2345678901234567890"}"""

    private fun model(vararg ids: String): String {
        val data = JSONObject()
        ids.forEach { id ->
            data.put(id, JSONObject("""{"video_model":{"video_list":[{"main_url":"https://example.com/$id.mp4","video_meta":{"codec_type":"h265","vwidth":1280,"vheight":720,"definition":"720p"},"encrypt_info":{"encrypt":false}}]}}"""))
        }
        return JSONObject().put("code",0).put("data",data).toString()
    }

    private fun source(clock: () -> Long = System::currentTimeMillis, reply: (Request) -> String): ContentRepository = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath == "/reading/bookapi/video_tab/multi_video_model/v1/") "" else reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build(), clock
    )

    @Test fun emptyPreloadRecoversUsingRegisteredIdentityAndCompatibleDeviceLevel() {
        val paths = mutableListOf<String>()
        val repo = source { request ->
            val path = request.url.encodedPath
            paths += path
            when (path) {
                oldPath -> ""
                registerPath -> { assertNull(request.header("Cookie")); registered }
                batchPath -> {
                    assertEquals("1345678901234567890",request.url.queryParameter("device_id"))
                    assertEquals("2345678901234567890",request.url.queryParameter("iid"))
                    val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                    assertEquals(1,body.getJSONObject("biz_param").getInt("device_level"))
                    assertEquals("123",body.getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0))
                    model("123")
                }
                else -> error("Unexpected path: $path")
            }
        }
        assertEquals("https://example.com/123.mp4",repo.stream("123").url)
        assertEquals(listOf(oldPath,registerPath,batchPath),paths)
    }

    @Test fun batchNeverReturnsTheWrongEpisode() {
        val repo = source { request ->
            when (request.url.encodedPath) {
                oldPath -> " "
                registerPath -> registered
                batchPath -> model("999")
                else -> error("Unexpected request")
            }
        }
        val failure = assertThrows(IOException::class.java) { repo.stream("123") }
        assertTrue(failure.message.orEmpty().contains("当前分集"))
    }

    @Test fun playbackAndRepeatedPreloadReuseTheirOwnBatchedModels() {
        var registrations = 0
        var batches = 0
        val repo = source { request ->
            when (request.url.encodedPath) {
                oldPath -> ""
                registerPath -> { registrations++; registered }
                batchPath -> {
                    batches++
                    val ids = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                        .getJSONObject("mixed_video_id_map").getJSONArray("1004")
                    assertEquals("123",ids.getString(0))
                    assertEquals("124",ids.getString(1))
                    assertEquals(8,ids.length())
                    model("123","124")
                }
                else -> error("Unexpected request")
            }
        }
        val window = (122..140).map(Int::toString)
        assertEquals("https://example.com/123.mp4",repo.streamLadder("123",window).renditions.single().url)
        val preload = repo.withPlaybackTransport(repo.http.newBuilder().build())
        assertEquals("https://example.com/124.mp4",preload.streamLadder("124",window).renditions.single().url)
        assertEquals("https://example.com/124.mp4",repo.streamLadder("124",window).renditions.single().url)
        assertEquals("https://example.com/123.mp4",repo.streamLadder("123",window).renditions.single().url)
        assertEquals(1,registrations)
        assertEquals(1,batches)
        repo.invalidatePlaybackModel("123")
        assertEquals("https://example.com/123.mp4",repo.streamLadder("123",window).renditions.single().url)
        assertEquals(1,registrations)
        assertEquals(2,batches)
    }

    @Test fun refusalDoesNotRegisterAgainDuringPlaybackRetryWindow() {
        var now = 1_800_000_000_000L
        var registrations = 0
        val repo = source(clock = { now }) { request ->
            when (request.url.encodedPath) {
                oldPath -> ""
                registerPath -> {
                    registrations++
                    if (registrations == 1) """{"device_id":0,"install_id":0}""" else registered
                }
                batchPath -> model("123")
                else -> error("Unexpected request")
            }
        }
        assertThrows(PlaybackRegistrationDeferredException::class.java) { repo.stream("123") }
        assertThrows(PlaybackRegistrationDeferredException::class.java) { repo.stream("123") }
        assertEquals(1,registrations)
        now += 61_000
        assertEquals("https://example.com/123.mp4",repo.stream("123").url)
        assertEquals(2,registrations)
    }

    @Test fun emptyPrimaryBatchUsesAlternateHostWithTheSameFreshRegistration() {
        assertAlternateBatchHost { "" }
    }

    @Test fun temporaryPrimaryBatchFailureUsesAlternateHost() {
        assertAlternateBatchHost { throw ContentHttpException(503) }
    }

    @Test fun rateLimitedRegistrationDefersRepeatedCalls() {
        var registrations = 0
        val repo = source { request ->
            when (request.url.encodedPath) {
                oldPath -> ""
                registerPath -> {
                    registrations++
                    throw ContentHttpException(429)
                }
                else -> error("Unexpected request")
            }
        }
        assertThrows(PlaybackRegistrationDeferredException::class.java) { repo.stream("123") }
        assertThrows(PlaybackRegistrationDeferredException::class.java) { repo.stream("123") }
        assertEquals(1,registrations)
    }

    @Test fun foregroundRecoversWhenEveryPlaybackHostReturnsEmptyForTheOldSession() {
        var registrations = 0
        val oldDevice = "1345678901234567890"
        val newDevice = "3345678901234567890"
        val batches = mutableListOf<String?>()
        val repo = source { request ->
            when (request.url.encodedPath) {
                oldPath -> ""
                registerPath -> if (++registrations == 1) registered else registered.replace(oldDevice, newDevice)
                batchPath -> {
                    val device = request.url.queryParameter("device_id")
                    batches += device
                    if (device == newDevice) model("123", "124") else ""
                }
                else -> error("Unexpected request")
            }
        }
        val prefetch = repo.withPlaybackTransport(repo.http.newBuilder().build())
        assertThrows(EmptyContentResponse::class.java) { prefetch.stream("123") }
        assertEquals(1, registrations)

        val window = listOf("123", "124")
        assertEquals("https://example.com/123.mp4", repo.streamLadder("123", window).renditions.single().url)
        assertEquals("https://example.com/124.mp4", prefetch.stream("124").url)
        assertEquals(listOf(oldDevice, oldDevice, oldDevice, oldDevice, newDevice), batches)
        assertEquals(2, registrations)
    }

    @Test fun repeatedEmptyResponsesDoNotContinuouslyRegisterDevices() {
        var now = 1_800_000_000_000L
        var registrations = 0
        val repo = source(clock = { now }) { request ->
            when (request.url.encodedPath) {
                registerPath -> { registrations++; registered }
                oldPath, batchPath -> ""
                else -> error("Unexpected request")
            }
        }
        repeat(3) { assertThrows(EmptyContentResponse::class.java) { repo.stream("123") } }
        assertEquals(2, registrations)
        now += 61_000
        assertThrows(EmptyContentResponse::class.java) { repo.stream("123") }
        assertEquals(3, registrations)
    }

    @Test fun mixedTransportFailuresDoNotRefreshTheSession() {
        var registrations = 0
        val repo = source { request ->
            when (request.url.encodedPath) {
                registerPath -> { registrations++; registered }
                oldPath -> ""
                batchPath -> if (request.url.host.contains("sinfonlineb")) throw ContentHttpException(503) else ""
                else -> error("Unexpected request")
            }
        }
        assertThrows(EmptyContentResponse::class.java) { repo.stream("123") }
        assertEquals(1, registrations)
    }

    private fun assertAlternateBatchHost(primary: () -> String) {
        val batchHosts = mutableListOf<String>()
        var registrations = 0
        val repo = source { request ->
            when (request.url.encodedPath) {
                oldPath -> ""
                registerPath -> { registrations++; registered }
                batchPath -> {
                    batchHosts += request.url.host
                    if (batchHosts.size == 1) primary() else model("123")
                }
                else -> error("Unexpected request")
            }
        }
        assertEquals("https://example.com/123.mp4",repo.stream("123").url)
        assertEquals(1,registrations)
        assertEquals(listOf("api5-normal-sinfonlineb.fqnovel.com","api5-normal-hl.fqnovel.com"),batchHosts)
    }
}
