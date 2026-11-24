// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobileSdkDigestOracleTest {
    @Test fun matchesFullSdkProofForFinalRequestBytesAcrossAllTimestampRotations() {
        // Expected bytes come from the fixed ARM64 SDK oracle, not the Kotlin signer.
        // Synthetic inputs exercise gzip, arbitrary binary and short nonempty bodies.
        val fixture = JSONObject(javaClass.getResource("/mobile-sdk-selector12.json")!!.readText())
        val rows = fixture.getJSONArray("vectors")
        assertEquals(32, rows.length())
        val rotations = mutableSetOf<Int>()
        val signer = MobileV5Signer({ 0L }, emptyMap(), { 0.0 }, "offline-synthetic-process", true)
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val seconds = row.getInt("seconds")
            rotations += seconds and 31
            val query = row.getString("query")
            val body = decode64(row.getString("bodyBase64"))
            val queryHash = sm3(query.toByteArray(Charsets.UTF_8))
            val bodyHash = digest("MD5", body)
            val secondsBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(seconds).array()
            assertEquals("input binding $index", row.getString("payloadSha256"),
                digest("SHA-256", queryHash + bodyHash + secondsBytes).hex())
            assertEquals("raw selector 12 maps to supported branch $index", 0, signer.branch(query, body, seconds))
            assertEquals("SDK proof including checksum $index", row.getString("expectedField13"),
                signer.hashF13(queryHash, bodyHash, seconds).hex())
        }
        assertEquals((0..31).toSet(), rotations)
    }
}
