package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompatibleResourcesTest {
    private val pinned="https://example.com/info?codec_type=4&force_fids=pinned&ptoken=keep&video_id=same"
    private fun media(vararg codecs: String): String {
        val rows=JSONObject()
        codecs.forEachIndexed { n,codec -> rows.put("video_$n",JSONObject().put("codec_type",codec)
            .put("vwidth",1280).put("vheight",720).put("main_url","https://example.com/$codec-$n")) }
        return JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
    }
    private fun repo(url:String=pinned, respond:(Request)->String): ContentRepository = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            val body=if(request.url.host=="example.com")respond(request) else JSONObject().put("data",
                JSONObject().put("123",JSONObject().put("video_model",JSONObject().put("fallback_api",url)))).toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build())

    @Test fun pinnedByteVc2RecoversUsingSameAuthorizedResourceAndOwnCodecMetadata() {
        val requests=mutableListOf<Request>()
        val source=repo { request ->
            requests+=request
            if(request.url.queryParameter("force_fids")!=null)media("bytevc2") else {
                assertEquals("1",request.url.queryParameter("codec_type"))
                assertEquals("keep",request.url.queryParameter("ptoken"))
                assertEquals("same",request.url.queryParameter("video_id"))
                media("h265")
            }
        }
        val result=source.streamLadder("123").renditions.single()
        assertEquals("h265",result.codec)
        assertEquals("https://example.com/h265-0",result.url)
        assertEquals(2,requests.size)
    }

    @Test fun compatibleOrMixedResponseNeverRewritesUrlOrAddsARequest() {
        for(codecs in listOf(arrayOf("h264"),arrayOf("bytevc2","h265"))) {
            var calls=0
            val result=repo { request -> calls++;assertNotNull(request.url.queryParameter("force_fids"));media(*codecs) }.streamLadder("123")
            assertEquals(1,calls);assertEquals(1,result.renditions.size)
        }
    }

    @Test fun stillUnsupportedAlternateStopsAfterOneRecovery() {
        var calls=0
        assertThrows(IncompatibleStreamException::class.java) { repo { calls++;media("bvc2") }.streamLadder("123") }
        assertEquals(2,calls)
    }

    @Test fun unsupportedUnpinnedResponseDoesNotRepeatIdenticalRequest() {
        var calls=0
        assertThrows(IncompatibleStreamException::class.java) { repo("https://example.com/info?codec_type=1") { calls++;media("bytevc2") }.streamLadder("123") }
        assertEquals(1,calls)
    }

    @Test fun malformedOriginalResponseDoesNotTriggerCodecRecovery() {
        var calls=0
        assertThrows(IOException::class.java) { repo { calls++;"{}" }.streamLadder("123") }
        assertEquals(1,calls)
    }

    @Test fun recoveryCancellationIsPropagatedWithoutFurtherRetries() {
        var calls=0
        assertThrows(InterruptedIOException::class.java) { repo { if(++calls==1)media("bytevc2") else throw InterruptedIOException("cancelled") }.streamLadder("123") }
        assertEquals(2,calls)
    }

    @Test fun recoveryRejectsEmptyVideoList() {
        var calls=0
        assertThrows(IOException::class.java) { repo { if(++calls==1)media("bytevc2") else media() }.streamLadder("123") }
        assertEquals(2,calls)
    }
}
