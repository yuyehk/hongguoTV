package com.hongguotv.core

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Cross-fork invariants: the new rendition parser must retain our identity/playback contract. */
class FeaturePortIntegrationTest {
    private fun repository(respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(respond(request).toResponseBody()).build()
        }.build()
    )

    private fun inlineModel(url: String) = JSONObject("""{
        "video_list":[{"video_meta":{"definition":"720p","codec_type":"h264","vwidth":720,"vheight":1280},
        "encrypt_info":{"encrypt":false},"main_url":"$url"}]
    }""")

    @Test fun inlineLadderUsesSignedStableIdentityAcrossRepositoryInstances() {
        val requests=mutableListOf<Request>()
        repeat(2) {
            val repository=repository { request ->
                requests+=request
                JSONObject().put("code",0).put("data",JSONObject().put("123",
                    JSONObject().put("video_model",inlineModel("https://example.com/episode-123")))).toString()
            }
            val rendition=repository.streamLadder("123").renditions.single()
            assertEquals("https://example.com/episode-123",rendition.url)
            assertEquals(720,rendition.width)
            assertEquals(1280,rendition.height)
        }
        assertEquals(2,requests.size)
        requests.forEach { request ->
            assertEquals("/novel/player/multi_video_model/preload/v1/",request.url.encodedPath)
            assertEquals(VendorConstants.device.getValue("device_id"),request.url.queryParameter("device_id"))
            assertNotNull(request.header("x-gorgon"))
            assertNotNull(request.header("x-medusa"))
        }
        assertEquals(requests[0].url.queryParameter("device_id"),requests[1].url.queryParameter("device_id"))
    }

    @Test fun inlineModelForAnotherEpisodeCannotBypassRequestedEpisodeCheck() {
        var requests=0
        val repository=repository {
            requests++
            JSONObject().put("code",0).put("data",JSONObject().put("999",
                JSONObject().put("video_model",inlineModel("https://example.com/wrong-episode")))).toString()
        }
        assertThrows(IOException::class.java) { repository.streamLadder("123") }
        assertEquals(1,requests)
    }

    @Test fun accountSignerDoesNotReplacePersistedAnonymousDeviceIdentity() {
        val original=VendorConstants.device.getValue("device_id")
        val accountDevice=VendorConstants.device + ("device_id" to "3999999999999999999")
        val request=Signer(device=accountDevice).sign("https://example.com/account",accountDevice)
        assertEquals("3999999999999999999",request.url.toHttpUrl().queryParameter("device_id"))
        assertEquals(original,VendorConstants.device.getValue("device_id"))
    }

    @Test fun upstreamWarmupApiAndExistingPreparedSlotWindowKeepSleepStopPrecedence() {
        assertTrue(PrefetchPolicy.nearEnd(20_000,60_000,1f))
        assertFalse(PrefetchPolicy.canStart(20_000,60_000,1f,3_000,10_000))
        assertTrue(PrefetchPolicy.canStart(35_000,60_000,1f,3_000,10_000))
        val timer=SleepTimer { 0L }
        timer.afterEpisodes(1)
        assertFalse(timer.canContinueAfter(25_000))
        assertEquals(1,timer.episodesLeft)
        assertTrue(timer.episodeEnded())
    }
}
