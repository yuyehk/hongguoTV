package com.hongguotv.core

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CurrentPlaybackProtocolTest {
    private val tabPath = "/reading/bookapi/video_tab/multi_video_model/v1/"
    private val registerPath = "/service/2/device_register/"

    private fun model(id: String): String = JSONObject().put("code", 0).put("data",
        JSONObject().put("video_model_data", JSONObject().put(id, JSONObject().put(
            "video_model", JSONObject("""{"video_list":[{"main_url":"https://example.com/$id.mp4","video_meta":{"codec_type":"h265","vwidth":1280,"vheight":720,"definition":"720p"},"encrypt_info":{"encrypt":false}}]}""")
        )))
    ).toString()

    private fun repository(reply: (Request) -> String): ContentRepository = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Set-Cookie", "install_id=anonymous; Path=/")
                .body(body.toResponseBody()).build()
        }.build()
    )

    @Test fun currentProtocolUsesFiveHeadersAndReusesOneRegistrationAcrossEpisodes() {
        var registrations = 0
        var tabRequests = 0
        val repository = repository { request ->
            when (request.url.encodedPath) {
                registerPath -> {
                    registrations++
                    assertNull(request.header("Cookie"))
                    """{"device_id_str":"1345678901234567890","install_id_str":"2345678901234567890"}"""
                }
                tabPath -> {
                    tabRequests++
                    assertEquals("GET", request.method)
                    assertEquals("73532", request.url.queryParameter("version_code"))
                    assertEquals("1345678901234567890", request.url.queryParameter("device_id"))
                    assertEquals("2345678901234567890", request.url.queryParameter("iid"))
                    assertEquals("1", request.url.queryParameter("device_level"))
                    assertNull(request.url.queryParameter("ts"))
                    assertNull(request.header("x-helios"))
                    assertNull(request.header("x-medusa"))
                    assertTrue(request.header("x-gorgon").orEmpty().startsWith("8404"))
                    assertTrue(request.header("x-argus").orEmpty().length > 100)
                    assertTrue(request.header("x-ladon").orEmpty().length > 20)
                    assertEquals("install_id=anonymous", request.header("Cookie"))
                    model(request.url.queryParameter("video_id")!!)
                }
                else -> ""
            }
        }
        assertEquals("https://example.com/123.mp4", repository.stream("123").url)
        val prefetch = repository.withPlaybackTransport(repository.http.newBuilder().build())
        assertEquals("https://example.com/124.mp4", prefetch.stream("124").url)
        assertEquals("https://example.com/123.mp4", repository.stream("123").url)
        assertEquals(1, registrations)
        assertEquals(2, tabRequests)
    }

    @Test fun unrelatedEpisodeFromTabIsNeverUsedForPlayback() {
        val repository = repository { request ->
            when (request.url.encodedPath) {
                registerPath -> """{"device_id_str":"1345678901234567890","install_id_str":"2345678901234567890"}"""
                tabPath -> model("999")
                else -> ""
            }
        }
        val failure = assertThrows(IOException::class.java) { repository.stream("123") }
        assertTrue(failure.message.orEmpty().contains("当前分集"))
    }

    @Test fun malformedLegacyResponseDoesNotBlockTheCurrentProtocol() {
        val repository = repository { request ->
            when (request.url.encodedPath) {
                registerPath -> """{"device_id_str":"1345678901234567890","install_id_str":"2345678901234567890"}"""
                tabPath -> model("123")
                else -> "<html>temporarily unavailable</html>"
            }
        }
        assertEquals("https://example.com/123.mp4", repository.stream("123").url)
    }

    @Test fun rejectedLegacySignatureDoesNotBlockTheCurrentProtocol() {
        val repository = repository { request ->
            when (request.url.encodedPath) {
                registerPath -> """{"device_id_str":"1345678901234567890","install_id_str":"2345678901234567890"}"""
                tabPath -> model("123")
                else -> """{"Code":110001}"""
            }
        }
        assertEquals("https://example.com/123.mp4", repository.stream("123").url)
    }

    @Test fun malformedPrimaryTabResponseUsesTheAlternateHost() {
        val hosts = mutableListOf<String>()
        val repository = repository { request ->
            when (request.url.encodedPath) {
                registerPath -> """{"device_id_str":"1345678901234567890","install_id_str":"2345678901234567890"}"""
                tabPath -> {
                    hosts += request.url.host
                    if (hosts.size == 1) "<html>temporarily unavailable</html>" else model("123")
                }
                else -> ""
            }
        }
        assertEquals("https://example.com/123.mp4", repository.stream("123").url)
        assertEquals(listOf("api5-normal-sinfonlineb.fqnovel.com", "reading.snssdk.com"), hosts)
    }

    @Test fun unusableExactModelDoesNotPoisonSubsequentRetries() {
        var requests = 0
        var registrations = 0
        val repository = repository { request ->
            when (request.url.encodedPath) {
                registerPath -> {
                    registrations++
                    """{"device_id_str":"1345678901234567890","install_id_str":"2345678901234567890"}"""
                }
                tabPath -> {
                    requests++
                    val response = JSONObject(model("123"))
                    if (requests == 1) {
                        response.getJSONObject("data").getJSONObject("video_model_data")
                            .getJSONObject("123").put("video_model", JSONObject())
                    }
                    response.toString()
                }
                else -> ""
            }
        }
        assertThrows(IOException::class.java) { repository.stream("123") }
        assertEquals("https://example.com/123.mp4", repository.stream("123").url)
        assertEquals(2, requests)
        assertEquals(1, registrations)
    }
}
