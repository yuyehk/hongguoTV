package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class DecoderPolicyTest {
    private data class Candidate(val name: String, val software: Boolean)
    private val candidates = listOf(Candidate("vendor", false), Candidate("c2.android", true), Candidate("vendor2", false))

    @Test fun automaticKeepsDeviceRanking() {
        assertEquals(candidates, DecoderPolicy.order(candidates, DecoderMode.AUTO) { it.software })
    }

    @Test fun softwarePreferenceKeepsHardwareAsFallback() {
        val result = DecoderPolicy.order(candidates, DecoderMode.SOFTWARE) { it.software }
        assertEquals(listOf("c2.android", "vendor", "vendor2"), result.map { it.name })
        assertEquals(candidates.size, result.size)
    }

    @Test fun hardwarePreferenceIsStableAndEmptySafe() {
        assertEquals(listOf("vendor", "vendor2", "c2.android"), DecoderPolicy.order(candidates, DecoderMode.HARDWARE) { it.software }.map { it.name })
        assertTrue(DecoderPolicy.order(emptyList<Candidate>(), DecoderMode.HARDWARE) { it.software }.isEmpty())
    }
}
