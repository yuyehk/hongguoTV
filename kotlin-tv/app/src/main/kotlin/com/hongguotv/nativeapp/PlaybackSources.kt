// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.util.Log
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.PlaybackQuality
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.Rendition
import com.hongguotv.core.VideoTracks
import okhttp3.OkHttpClient

internal class OpenedStream(
    val video: RemoteVideo,
    val rendition: Rendition?,
    val tiers: List<Rendition>,
    val track: VideoTracks.Track?,
)

/**
 * Opens the first rendition the device can actually decode: select by API metadata, then verify the
 * coded size read from the MP4 header and step down the ladder on mismatch.
 */
internal fun openStream(
    http: OkHttpClient,
    repository: ContentRepository,
    id: String,
    maxQuality: Int,
    supportsRendition: (Rendition) -> Boolean,
    initialBytes: Int = 256 * 1024,
    supportsTrack: (VideoTracks.Track?) -> Boolean,
): OpenedStream? {
    val ladder = repository.streamLadder(id)
    for (rendition in ladder.playbackOrder(maxQuality, supportsRendition)) {
        val video = RemoteVideo(http, rendition.toStreamInfo())
        try {
            video.prepare(initialBytes)
            val track = video.trackInfo()
            if (supportsTrack(track))
                return OpenedStream(video, rendition, ladder.renditions, track)
            Log.i(
                "HongguoTV",
                "Skipped ${rendition.quality}P ${rendition.codec}: coded ${track?.width}x${track?.height} beyond decoder capability",
            )
            video.close()
        } catch (error: Exception) {
            video.close()
            throw error
        }
    }
    return null
}

/** Next manual retry tier: only offer a lower option than what was already attempted. */
internal fun nextLowerQuality(attempted: Int): Int? =
    PlaybackQuality.options.filter { it < attempted }.maxOrNull()

/**
 * Auto-downgrade is worth another attempt while budget remains and a strictly lower tier exists.
 */
internal fun shouldAutoDowngrade(
    attempts: Int,
    maxAttempts: Int,
    tiers: List<Rendition>,
    currentQuality: Int,
): Boolean =
    attempts < maxAttempts &&
        currentQuality > 0 &&
        tiers.any { it.quality in 1 until currentQuality }
