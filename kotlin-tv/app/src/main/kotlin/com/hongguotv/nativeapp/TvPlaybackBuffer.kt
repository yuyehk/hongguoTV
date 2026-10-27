// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator

/** One shared player allocator bounds the current episode and its prepared successor. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object TvPlaybackBuffer {
    const val TARGET_BYTES = 12 * 1024 * 1024
    const val NEXT_DURATION_US = 3_000_000L

    fun create(): LoadControl {
        val control = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(15_000, 30_000, 1_000, 2_000)
            .setTargetBufferBytes(TARGET_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        return object : LoadControl {
            override fun onPrepared(playerId: PlayerId) = control.onPrepared(playerId)
            override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<ExoTrackSelection?>) =
                control.onTracksSelected(parameters, trackGroups, trackSelections)
            override fun onStopped(playerId: PlayerId) = control.onStopped(playerId)
            override fun onReleased(playerId: PlayerId) = control.onReleased(playerId)
            override fun getAllocator(playerId: PlayerId) = control.getAllocator(playerId)
            override fun getBackBufferDurationUs(playerId: PlayerId) = control.getBackBufferDurationUs(playerId)
            override fun retainBackBufferFromKeyframe(playerId: PlayerId) = control.retainBackBufferFromKeyframe(playerId)
            override fun shouldContinueLoading(parameters: LoadControl.Parameters) = control.shouldContinueLoading(parameters)
            override fun shouldStartPlayback(parameters: LoadControl.Parameters) = control.shouldStartPlayback(parameters)
            override fun shouldContinuePreloading(
                playerId: PlayerId,
                timeline: Timeline,
                mediaPeriodId: MediaSource.MediaPeriodId,
                bufferedDurationUs: Long,
            ): Boolean = control.getAllocator(playerId).totalBytesAllocated < TARGET_BYTES &&
                control.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
        }
    }
}
