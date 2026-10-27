package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class PlaybackSourcesTest {
    @Test
    fun manualRetryOnlyOffersLowerTierThanAttempted() {
        assertEquals(720, nextLowerQuality(1080))
        assertEquals(480, nextLowerQuality(720))
        assertNull(nextLowerQuality(480))
    }

    @Test
    fun autoDowngradeNeedsBudgetLowerTierAndKnownQuality() {
        val tiers =
            listOf(
                com.hongguotv.core.Rendition(1080, "h264", 1080, 1920, "u1", null),
                com.hongguotv.core.Rendition(480, "h264", 480, 854, "u2", null),
            )
        assertTrue(shouldAutoDowngrade(0, 2, tiers, 1080))
        assertFalse(shouldAutoDowngrade(2, 2, tiers, 1080)) // budget exhausted
        assertFalse(shouldAutoDowngrade(0, 2, tiers, 480)) // nothing lower
        assertFalse(shouldAutoDowngrade(0, 2, emptyList(), 1080))
        assertFalse(shouldAutoDowngrade(0, 2, tiers, 0)) // unknown attempted quality
    }
}
