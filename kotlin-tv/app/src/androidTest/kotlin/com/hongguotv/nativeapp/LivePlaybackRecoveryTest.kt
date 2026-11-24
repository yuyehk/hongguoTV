package com.hongguotv.nativeapp

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.Detail
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in, real-media acceptance: -e livePlaybackRecovery true.
 * Run only an isolated debug build with applicationId com.hongguotv.nativeapp.localrecovery01115.
 * No repository/transport replacement, seek, speed-up, website playback or synthetic media is used.
 * The test invokes the existing episode-selection entry point after resolving real catalog metadata.
 * Its result is local emulator evidence, never evidence about the user's TV or retained TV account.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LivePlaybackRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val events = Collections.synchronizedList(mutableListOf<JSONObject>())
    private var activity: MainActivity? = null
    private val epoch = SystemClock.elapsedRealtime()
    private var passed = false

    private fun <T> ui(action: () -> T): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try { result.set(action()) } catch (error: Throwable) { failure.set(error) }
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

    private fun event(name: String, vararg values: Pair<String, Any>) {
        val row = JSONObject().put("event", name).put("elapsedMs", SystemClock.elapsedRealtime() - epoch)
        values.forEach { (key, value) -> row.put(key, value) }
        events.add(row)
        Log.i(TAG, row.toString())
    }

    private fun resolveDetail(repository: ContentRepository): Detail {
        // Catalog access only supplies the actual series/episode IDs; video uses App native APIs.
        val detail = repository.detail(SERIES)
        assertTrue("Live catalog must identify the seventh season",
            detail.series.title.contains("第七季") || detail.series.title.contains("第7季"))
        assertEquals("Live catalog must confirm the requested first two episode IDs",
            listOf(FIRST, SECOND), detail.episodes.take(2))
        return detail
    }

    @Test fun firstEpisodeNaturallyEndsAndSecondPlays() {
        assumeTrue("Explicit live-media opt-in is required",
            InstrumentationRegistry.getArguments().getString("livePlaybackRecovery") == "true")
        val context = instrumentation.targetContext
        assertEquals("Never run this acceptance against the user's installed package", PACKAGE, context.packageName)
        assertTrue("An isolated debug build is required",
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertTrue("The isolated package must not contain account credentials",
            context.getSharedPreferences("cloud_account_secure", 0).all.isEmpty())

        var preferences: FeaturePreferences? = null
        var oldNextSeason = false
        var oldAutoSkip = false
        var oldSkip: FeatureSkipMark? = null
        var seriesId: String? = null
        var oldAutoNext = false
        var oldSpeed = 1f
        var settingsChanged = false
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val loadDeadline = SystemClock.elapsedRealtime() + 30000
            while (!ui { (field("library") as Library).isLoaded }) {
                check(SystemClock.elapsedRealtime() < loadDeadline) { "Local library loading timed out" }
                Thread.sleep(50)
            }
            val repository = ui {
                MainActivity::class.java.getDeclaredMethod("invalidatePage").apply { isAccessible = true }.invoke(activity)
                assertFalse("The isolated package must remain signed out", (field("cloudAccount") as AccountController).signedIn)
                field("repository") as ContentRepository
            }
            val detail = resolveDetail(repository)
            seriesId = detail.series.id
            event("catalog-confirmed", "seriesId" to detail.series.id, "first" to FIRST, "second" to SECOND)
            ui {
                val library = field("library") as Library
                oldAutoNext = library.autoNext
                oldSpeed = library.playbackSpeed
                preferences = FeaturePreferences(context)
                oldNextSeason = preferences!!.nextSeason
                oldAutoSkip = preferences!!.autoSkipIncompatible
                oldSkip = preferences!!.skip(detail.series.id)
                settingsChanged = true
                library.autoNext = true
                library.playbackSpeed = 1f
                preferences!!.nextSeason = false
                preferences!!.autoSkipIncompatible = false
                preferences!!.setSkip(detail.series.id, 0, 0)
                (field("sleepTimer") as SleepTimer).cancel()
                MainActivity::class.java.getDeclaredField("detail").apply { isAccessible = true }.set(activity, detail)
                MainActivity::class.java.getDeclaredMethod("playEpisode", Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(activity, 0, 0L, true, false, false)
                event("requested", "episodeId" to FIRST, "positionMs" to 0, "speed" to 1)
            }

            val seenPlayers = Collections.newSetFromMap(IdentityHashMap<ExoPlayer, Boolean>())
            val firstFrame = AtomicLong(-1)
            val secondFrame = AtomicLong(-1)
            val firstFrameCount = AtomicLong()
            val secondFrameCount = AtomicLong()
            var firstDuration = 0L
            var firstMaximum = 0L
            var firstObservedPosition: Long? = null
            var naturalBoundary = false
            var endedFirst = false
            var forbiddenSeek = false
            var playerFailure: String? = null
            var lastReport = 0L
            var lastEpisode = ""
            var lastPosition = 0L
            var lastSampleAt = SystemClock.elapsedRealtime()
            val deadline = SystemClock.elapsedRealtime() + 600000
            while (SystemClock.elapsedRealtime() < deadline) {
                val complete = ui {
                    check(field("playError") != true) { "Production playback reported an error" }
                    val player = field("player") as? ExoPlayer ?: return@ui false
                    if (seenPlayers.add(player)) {
                        val frameEpisode = AtomicReference(player.currentMediaItem?.mediaId.orEmpty())
                        player.addListener(object : Player.Listener {
                            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                                frameEpisode.set(mediaItem?.mediaId.orEmpty())
                                event("media-item", "episodeId" to frameEpisode.get(), "reason" to reason)
                            }
                            override fun onPlaybackStateChanged(state: Int) {
                                if (state == Player.STATE_ENDED && player.currentMediaItem?.mediaId == FIRST) {
                                    endedFirst = true
                                    firstMaximum = maxOf(firstMaximum, player.currentPosition)
                                    event("native-ended", "episodeId" to FIRST, "positionMs" to player.currentPosition)
                                }
                            }
                            override fun onPlayerError(error: PlaybackException) {
                                playerFailure = error.errorCodeName // Never record URLs, headers or nested causes.
                            }
                            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
                                newPosition: Player.PositionInfo, reason: Int) {
                                if (reason == Player.DISCONTINUITY_REASON_SEEK && newPosition.positionMs > 1000) forbiddenSeek = true
                                if (oldPosition.mediaItem?.mediaId == FIRST && newPosition.mediaItem?.mediaId == SECOND) {
                                    naturalBoundary = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                                    firstMaximum = maxOf(firstMaximum, oldPosition.positionMs)
                                    event("boundary", "automatic" to naturalBoundary, "oldPositionMs" to oldPosition.positionMs,
                                        "newPositionMs" to newPosition.positionMs, "reason" to reason)
                                }
                            }
                        })
                        player.setVideoFrameMetadataListener { _, _, _, _ ->
                            val now = SystemClock.elapsedRealtime()
                            when (frameEpisode.get()) {
                                FIRST -> {
                                    firstFrameCount.incrementAndGet()
                                    if (firstFrame.compareAndSet(-1, now)) event("first-observed-frame", "episodeId" to FIRST)
                                }
                                SECOND -> {
                                    secondFrameCount.incrementAndGet()
                                    if (secondFrame.compareAndSet(-1, now)) event("first-observed-frame", "episodeId" to SECOND)
                                }
                            }
                        }
                    }
                    check(playerFailure == null) { "Media3 error: $playerFailure" }
                    check(!forbiddenSeek) { "A seek occurred during natural-playback acceptance" }
                    check(player.playbackParameters.speed == 1f) { "Playback speed changed" }
                    val id = player.currentMediaItem?.mediaId.orEmpty()
                    val position = player.currentPosition
                    val now = SystemClock.elapsedRealtime()
                    if (id == lastEpisode) {
                        check(position <= lastPosition + (now - lastSampleAt) + 1500) { "Playback clock jumped forward" }
                    }
                    lastEpisode = id
                    lastPosition = position
                    lastSampleAt = now
                    if (id == FIRST) {
                        if (firstObservedPosition == null) firstObservedPosition = position
                        firstDuration = maxOf(firstDuration, player.duration)
                        firstMaximum = maxOf(firstMaximum, position)
                    }
                    if (now - lastReport >= 10000) {
                        event("progress", "episodeId" to id, "positionMs" to position,
                            "durationMs" to player.duration, "state" to player.playbackState,
                            "isPlaying" to player.isPlaying, "playlistItems" to player.mediaItemCount)
                        lastReport = now
                    }
                    id == SECOND && position >= 10000 && player.isPlaying && secondFrameCount.get() >= 10
                }
                if (complete) {
                    assertTrue("The first episode must start at 00:00", (firstObservedPosition ?: Long.MAX_VALUE) <= 1000)
                    assertTrue("Reject preview-length media", firstDuration >= 60000)
                    assertTrue("The first episode must actually decode video", firstFrameCount.get() > 100)
                    assertTrue("The natural episode boundary was not observed", naturalBoundary || endedFirst)
                    assertTrue("The first episode did not reach its actual tail", firstMaximum >= firstDuration - 1500)
                    assertTrue("Wall-clock duration must cover the full first episode",
                        secondFrame.get() - firstFrame.get() >= firstDuration - 2500)
                    ui {
                        val library = field("library") as Library
                        MainActivity::class.java.getDeclaredMethod("saveProgress", Boolean::class.javaPrimitiveType)
                            .apply { isAccessible = true }.invoke(activity, false)
                        library.flush()
                        val progress = library.snapshot().history.single { it.series.id == detail.series.id }
                        assertEquals("Local history must advance to episode 2", 1, progress.episodeIndex)
                        assertTrue("Local history must record episode 2 progress", progress.position >= 10000)
                    }
                    passed = true
                    event("PASS", "firstDurationMs" to firstDuration, "firstTailMs" to firstMaximum,
                        "firstDecodedFrames" to firstFrameCount.get(), "secondDecodedFrames" to secondFrameCount.get(),
                        "naturalPlaylistTransition" to naturalBoundary, "nativeEndedTransition" to endedFirst,
                        "playersObserved" to seenPlayers.size)
                    break
                }
                Thread.sleep(100)
            }
            assertTrue("Timed out before full first episode and automatic second-episode playback", passed)
        } catch (failure: Throwable) {
            val safeMessage = failure.message.orEmpty().replace(Regex("https?://\\S+"), "[URL]").take(300)
            event("FAIL", "type" to failure.javaClass.simpleName, "message" to safeMessage)
            throw AssertionError("Live playback acceptance failed: ${failure.javaClass.simpleName}: $safeMessage")
        } finally {
            ui {
                if (settingsChanged) {
                    (field("library") as Library).apply { autoNext = oldAutoNext; playbackSpeed = oldSpeed }
                    preferences?.apply {
                        nextSeason = oldNextSeason
                        autoSkipIncompatible = oldAutoSkip
                        oldSkip?.let { setSkip(seriesId!!, it.introSeconds, it.outroSeconds) }
                    }
                }
                activity?.finish()
            }
            instrumentation.waitForIdleSync()
            val evidence = JSONObject().put("passed", passed).put("package", context.packageName)
                .put("scope", "Isolated local App native Media3 playback; not physical-TV acceptance")
                .put("events", synchronized(events) { JSONArray(events.toList()) })
            context.filesDir.resolve("live-playback-recovery.json").writeText(evidence.toString(2))
        }
    }

    private companion object {
        const val TAG = "LivePlaybackRecovery"
        const val PACKAGE = "com.hongguotv.nativeapp.localrecovery01115"
        const val SERIES = "7707078673207151678"
        const val FIRST = "7707465250475346969"
        const val SECOND = "7707465250475363353"
    }
}
