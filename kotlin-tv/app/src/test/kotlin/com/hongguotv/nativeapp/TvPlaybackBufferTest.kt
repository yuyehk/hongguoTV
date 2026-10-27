// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvPlaybackBufferTest {
    @Test fun successorStopsLoadingAtTheSharedPlaybackMemoryBudget() {
        val control=TvPlaybackBuffer.create()
        val player=PlayerId("budget-fixture")
        control.onPrepared(player)
        val allocator=control.getAllocator(player)
        val blocks=mutableListOf<androidx.media3.exoplayer.upstream.Allocation>()
        try {
            while(allocator.totalBytesAllocated < TvPlaybackBuffer.TARGET_BYTES) blocks += allocator.allocate()
            assertFalse(control.shouldContinuePreloading(player,Timeline.EMPTY,MediaSource.MediaPeriodId("queued"),0))
        } finally {
            blocks.forEach(allocator::release)
            control.onReleased(player)
        }
    }
}
