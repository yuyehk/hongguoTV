package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSignerTest {
    @Test fun signaturesMatchIndependentReferenceForGetAndPost() {
        val fixture = JSONObject(javaClass.getResource("/playback-signing.json")!!.readText())
        val expected = fixture.getJSONArray("cases")
        val values = linkedMapOf(
            "aid" to "8662",
            "device_id" to "1234567890123456789",
            "iid" to "2234567890123456789",
            "resolution" to "1280*2772",
        )
        val signer = PlaybackSigner(clock = { 1800000000123L }, random = { 0.25 })
        for (index in 0 until expected.length()) {
            val sample = expected.getJSONObject(index)
            val body = if (sample.isNull("body")) null else sample.getString("body").toByteArray()
            val signed = signer.sign("https://example.com/player", values, body)
            assertEquals(sample.getString("url"), signed.url)
            val headers = sample.getJSONObject("headers")
            assertEquals(headers.length(), signed.headers.size)
            for (name in headers.keys()) {
                assertEquals(name, headers.getString(name), signed.headers[name])
            }
        }
    }
}
