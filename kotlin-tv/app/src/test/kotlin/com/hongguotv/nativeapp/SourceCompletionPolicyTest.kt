package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class SourceCompletionPolicyTest {
    @Test fun nextTransitionHonorsSleepForegroundAndBounds() {
        assertEquals(2, SourcePlaybackPolicy.target(1, 3, 1, true, false))
        assertNull(SourcePlaybackPolicy.target(1, 3, 1, true, true))
        assertNull(SourcePlaybackPolicy.target(1, 3, 1, false, false))
        assertNull(SourcePlaybackPolicy.target(2, 3, 1, true, false))
        assertEquals(0, SourcePlaybackPolicy.target(1, 3, -1, true, false))
    }
    @Test fun automaticCodecSkipRequiresRequestedPlaybackAndIdentifiedCodec() {
        assertTrue(SourcePlaybackPolicy.canAutoSkip(true,true,false,4001))
        assertFalse(SourcePlaybackPolicy.canAutoSkip(false,true,false,4001))
        assertFalse(SourcePlaybackPolicy.canAutoSkip(true,false,false,4001))
        assertFalse(SourcePlaybackPolicy.canAutoSkip(true,true,true,4001))
        assertFalse(SourcePlaybackPolicy.canAutoSkip(true,true,false,4002))
        assertFalse(SourcePlaybackPolicy.canAutoSkip(true,true,false,2004))
    }
    @Test fun refreshedSignedUrlRetainsStableSourceProgressIdentity() {
        val a = SourcePlaybackPolicy.progressKey("sourceA", "video1", "line", "episode1")
        assertEquals(a, SourcePlaybackPolicy.progressKey("sourceA", "video1", "line", "episode1"))
        assertNotEquals(a, SourcePlaybackPolicy.progressKey("sourceB", "video1", "line", "episode1"))
    }
    @Test fun measuredPreferenceRespectsManualOverrideAndFailures() {
        assertEquals(listOf("manual", "fast", "slow", "failed"), SourcePreference.order(
            listOf("slow", "failed", "fast", "manual"), "manual",
            mapOf("slow" to SourceMeasurement(900, true), "fast" to SourceMeasurement(100, true), "failed" to SourceMeasurement(5, false))))
    }
    @Test fun codecSkipBudgetIsBoundedAndResetOnlyAfterRendering() {
        val budget = SourceSkipBudget()
        repeat(5) { assertTrue(budget.allow(true, true)) }
        assertFalse(budget.allow(true, true))
        budget.rendered()
        assertFalse(budget.allow(false, true))
        assertFalse(budget.allow(true, false))
        assertTrue(budget.allow(true, true))
    }
    @Test fun skipDurationsCannotSeekPastAClipOrOverlapItsIntro() {
        assertEquals(0L, SourcePlaybackPolicy.introPosition(300_000, 500))
        assertEquals(2500L, SourcePlaybackPolicy.introPosition(300_000, 3500))
        assertEquals(1000L, SourcePlaybackPolicy.introPosition(1000, 3500))
        assertEquals(3000L, SourcePlaybackPolicy.outroStart(300_000, 3500, 2500))
    }
    @Test fun receiveRateKeepsAllBytesFromBurstAndExpiresIdleSamples() {
        var now = 0L
        val meter = SourceReceiveRate { now }
        repeat(1000) { meter.add(1024) }
        assertEquals("1000 KB/s", meter.label())
        now = 2001; assertEquals("0 KB/s", meter.label())
        meter.add(1024); assertEquals("1 KB/s", meter.label())
        meter.reset(); assertEquals("0 KB/s", meter.label())
    }
}
