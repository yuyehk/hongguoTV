package com.hongguotv.core

import java.io.IOException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class IncompatibleStreamTest {
    private fun repository(answer: (Request) -> String) = ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
            .message("OK").body(answer(chain.request()).toResponseBody()).build()
    }.build())

    @Test fun knownLegacyIncompatibilityHasTypedFailureButMalformedSiblingDoesNot() {
        val known = """{"main_url":"https://example.com/video","codec_type":"ByteVC2"}"""
        fun stream(extra: String) = repository { request ->
            if (request.url.host == "example.com")
                """{"video_info":{"data":{"video_list":{"a":$known$extra}}}}"""
            else """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/info"}}}}"""
        }
        assertThrows(IncompatibleStreamException::class.java) { stream("").streamLadder("123") }
        val malformed = assertThrows(IOException::class.java) {
            stream(",\"b\":{\"main_url\":\"invalid\",\"codec_type\":\"h264\"}").streamLadder("123")
        }
        assertFalse(malformed is IncompatibleStreamException)
        val nonObject = assertThrows(IOException::class.java) {
            stream(",\"b\":\"broken\"").streamLadder("123")
        }
        assertFalse(nonObject is IncompatibleStreamException)
    }

    @Test fun knownInlineOnlyCodecIsTypedButAuthenticationAndMissingRequestedEpisodeAreNot() {
        val inline = """{"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.com/video","video_meta":{"codec_type":"bvc2","width":720,"height":1280},"encrypt_info":{"encrypt":false}}]}}}}"""
        assertThrows(IncompatibleStreamException::class.java) { repository { inline }.streamLadder("123") }
        for (raw in listOf("""{"Code":110001}""", """{"data":{"999":{"video_model":{}}}}""", """{"data":{"123":{"video_model":{}}}}""")) {
            assertFalse(assertThrows(IOException::class.java) { repository { raw }.streamLadder("123") } is IncompatibleStreamException)
        }
        val malformed = inline.replace("\"width\":720,\"height\":1280", "\"width\":0,\"height\":0")
        assertFalse(assertThrows(IOException::class.java) { repository { malformed }.streamLadder("123") } is IncompatibleStreamException)
        val nonObject = inline.replace("]}}}}", ",\"broken\"]}}}}")
        assertFalse(assertThrows(IOException::class.java) { repository { nonObject }.streamLadder("123") } is IncompatibleStreamException)
    }
}
