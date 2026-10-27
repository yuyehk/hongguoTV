// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class FeaturePolicyTest {
    @Test fun introIsSkippedOnlyOnceUntilPlaybackRestarts() {
        val state = FeatureSkipState()
        val mark = FeatureSkipMark(10, 5)
        assertEquals(10_000L, state.target("1", 0, 60_000, mark))
        assertNull(state.target("1", 0, 60_000, mark))
        state.started("1")
        assertEquals(10_000L, state.target("1", 5_000, 60_000, mark))
    }

    @Test fun outroWaitsForBoundaryAndNeverLoopsAtEnd() {
        val state = FeatureSkipState()
        val mark = FeatureSkipMark(0, 10)
        assertNull(state.target("1", 49_999, 60_000, mark))
        assertEquals(60_000L, state.target("1", 50_000, 60_000, mark))
        assertNull(state.target("1", 59_999, 60_000, mark))
    }

    @Test fun unknownDurationAndOverlappingMarksDoNotSeek() {
        val state = FeatureSkipState()
        assertNull(state.target("1", 0, 0, FeatureSkipMark(10, 10)))
        assertNull(state.target("1", 0, 15_000, FeatureSkipMark(10, 10)))
        assertNull(state.target("1", -1, 60_000, FeatureSkipMark(10, 10)))
    }

    @Test fun invalidPreferencesNormalizeToSupportedValues() {
        assertEquals(10, FeaturePolicy.seekStep(-9))
        assertEquals(30, FeaturePolicy.seekStep(30))
        assertEquals(3f, FeaturePolicy.holdSpeed(Float.NaN))
        assertEquals(4f, FeaturePolicy.holdSpeed(4f))
        assertEquals(FeatureSkipMark(0, 600), FeaturePolicy.skipMark(-1, 9999))
        assertEquals("我 的分组", FeaturePolicy.groupName("  我\n 的分组  "))
    }
}
