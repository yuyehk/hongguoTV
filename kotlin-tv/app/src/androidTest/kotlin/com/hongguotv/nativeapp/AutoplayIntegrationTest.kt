package com.hongguotv.nativeapp

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hongguotv.core.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real API-34 Activity/player; deterministic MP4 bytes and vendor responses replace external IO only. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AutoplayIntegrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private var activity: MainActivity?=null
    private var http: OkHttpClient?=null
    private var playbackRequestedAt=0L
    private val queuedReadFailures=AtomicInteger()
    private val warmupStarted=java.util.concurrent.CountDownLatch(1)
    private val warmupFinished=java.util.concurrent.CountDownLatch(1)
    private fun <T> ui(action: ()->T): T {
        val result=AtomicReference<T>(); val failure=AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { result.set(action()) } catch(t: Throwable) { failure.set(t) } }
        failure.get()?.let { throw it }; return result.get()
    }
    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
    private fun waitFor(timeout: Long=20000,condition: ()->Boolean) {
        val deadline=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<deadline) { if(ui(condition)) return; Thread.sleep(50) }
        fail("Timed out waiting for playback condition")
    }
    private fun start(failNext: Boolean=false,failQueuedRead: Boolean=false,delayNextMillis: Long=0,longCurrent: Boolean=false,introSeconds:Int=0,outroSeconds:Int=0): ExoPlayer {
        // Only optional test policies are reset; the upgrade fixture's real local library survives.
        instrumentation.targetContext.getSharedPreferences("feature-tools-v1",0).edit().clear().commit()
        FeaturePreferences(instrumentation.targetContext).nextSeason=false
        val bytes=instrumentation.context.assets.open("autoplay-test.mp4").use { it.readBytes() }
        val longBytes=if(longCurrent) instrumentation.context.assets.open("manual-next-long.mp4").use { it.readBytes() } else bytes
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request(); val url=request.url
            val builder=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("OK")
            if(url.host=="media.fixture.invalid") {
                val mediaBytes=if(url.encodedPath.endsWith("1001")) longBytes else bytes
                val range=Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.header("Range")!!)!!
                val start=range.groupValues[1].toInt()
                if(failQueuedRead && url.encodedPath.endsWith("1002") && start>=1024*1024) {
                    queuedReadFailures.incrementAndGet(); throw java.io.IOException("Speculative media read failed")
                }
                val end=minOf(range.groupValues[2].toInt(),mediaBytes.lastIndex)
                builder.code(206).header("Content-Range","bytes $start-$end/${mediaBytes.size}")
                    .body(mediaBytes.copyOfRange(start,end+1).toResponseBody()).build()
            } else if(url.host=="info.fixture.invalid") {
                builder.code(200).body("""{"video_info":{"data":{"video_list":{"v":{"height":180,"main_url":"https://media.fixture.invalid${url.encodedPath}"}}}}}""".toResponseBody()).build()
            } else {
                val body=okio.Buffer(); request.body!!.writeTo(body)
                val id=JSONObject(body.readUtf8()).getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0)
                if(id!="1001" && delayNextMillis>0) {
                    warmupStarted.countDown()
                    try { Thread.sleep(delayNextMillis) } finally { warmupFinished.countDown() }
                }
                val response=if(failNext && id!="1001") """{"code":110001}""" else
                    """{"code":0,"data":{"$id":{"video_model":{"fallback_api":"https://info.fixture.invalid/$id"}}}}"""
                builder.code(200).body(response.toResponseBody()).build()
            }
        }.build()
        http=client
        activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        waitFor { (field("library") as Library).isLoaded }
        ui {
            val a=activity!!
            MainActivity::class.java.getDeclaredMethod("invalidatePage").apply { isAccessible=true }.invoke(a)
            MainActivity::class.java.getDeclaredField("repository").apply { isAccessible=true }.set(a,ContentRepository(client))
            MainActivity::class.java.getDeclaredField("detail").apply { isAccessible=true }.set(a,
                Detail(Series("1000","Autoplay test"),listOf("1001","1002","1003","1004")))
            (field("library") as Library).autoNext=true
            (field("library") as Library).playbackSpeed=1f
            (field("sleepTimer") as SleepTimer).cancel()
            FeaturePreferences(instrumentation.targetContext).setSkip("1000",introSeconds,outroSeconds)
            playbackRequestedAt=android.os.SystemClock.elapsedRealtime()
            MainActivity::class.java.getDeclaredMethod("playEpisode",Int::class.javaPrimitiveType,Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(a,0,0L,true,false,false)
        }
        waitFor { (field("player") as? ExoPlayer)?.playbackState==Player.STATE_READY }
        return ui { field("player") as ExoPlayer }
    }
    @After fun close() {
        ui { activity?.finish() }; instrumentation.waitForIdleSync()
        http?.let { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown() }
    }
    @Test fun twoAutomaticBoundariesDoNotResetOrRebufferThePlayer() {
        val player=start(); val buffering=AtomicInteger(); val failures=AtomicInteger()
        val previousPts=java.util.concurrent.atomic.AtomicLong(-1)
        val previousRelease=java.util.concurrent.atomic.AtomicLong(-1)
        val frameGaps=java.util.concurrent.CopyOnWriteArrayList<Long>()
        ui { player.setVideoFrameMetadataListener { pts,release,_,_ ->
            val lastPts=previousPts.getAndSet(pts); val lastRelease=previousRelease.getAndSet(release)
            if(lastPts>pts+1000000 && lastRelease>0) frameGaps.add((release-lastRelease)/1000000)
        }; player.addListener(object: Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if(state==Player.STATE_BUFFERING) buffering.incrementAndGet() }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failures.incrementAndGet() }
        }) }
        waitFor(40000) { (field("episodeIndex") as Int)>=2 }
        assertEquals("Automatic boundaries must keep the prepared playlist running",0,buffering.get())
        assertEquals(0,failures.get()); assertSame(player,ui { field("player") })
        assertTrue(ui { player.mediaItemCount<=2 })
        val history=ui { (field("library") as Library).snapshot().history.single { it.series.id=="1000" } }
        assertTrue(history.episodeIndex>=1)
        android.util.Log.i("AutoplayTest","Two auto boundaries: rebufferEvents=${buffering.get()} frameGapsMs=$frameGaps")
    }
    @Test fun afterCurrentEpisodeStopsBeforeTheAlreadyQueuedNextItem() {
        val player=start()
        waitFor { player.mediaItemCount==2 }
        ui { (field("sleepTimer") as SleepTimer).afterEpisodes(1) }
        waitFor { player.mediaItemCount==1 }
        waitFor(20000) { field("sleepStopped")==true }
        assertEquals(0,ui { field("episodeIndex") })
        assertFalse(ui { player.playWhenReady })
    }
    @Test fun failedSpeculativeResolutionLeavesCurrentEpisodePlaying() {
        val player=start(failNext=true)
        Thread.sleep(5500)
        assertEquals(0,ui { field("episodeIndex") })
        assertTrue(ui { player.isPlaying }); assertNull(ui { player.playerError })
        assertEquals(1,ui { player.mediaItemCount })
    }
    @Test fun failedQueuedReadDoesNotStopTheCurrentEpisode() {
        val player=start(failQueuedRead=true)
        waitFor { queuedReadFailures.get()>0 }
        Thread.sleep(500)
        assertEquals(0,ui { field("episodeIndex") })
        assertTrue(ui { player.isPlaying }); assertNull(ui { player.playerError })
        assertEquals(1,ui { player.mediaItemCount })
    }
    @Test fun openingEpisodePickerCancelsAnInFlightWarmup() {
        val player=start(delayNextMillis=5000)
        assertTrue(warmupStarted.await(10,java.util.concurrent.TimeUnit.SECONDS))
        ui { MainActivity::class.java.getDeclaredMethod("showEpisodePanel",android.view.View::class.java)
            .apply { isAccessible=true }.invoke(activity,field("hud") as android.view.View) }
        assertTrue("Warm-up must be cancelled while the episode picker pauses playback",
            warmupFinished.await(2500,java.util.concurrent.TimeUnit.MILLISECONDS))
        instrumentation.waitForIdleSync()
        assertEquals(1,ui { player.mediaItemCount }); assertFalse(ui { player.playWhenReady })
        assertNull(ui { field("queuedNext") })
    }
    @Test fun backgroundingReleasesTheQueuedEpisode() {
        start(); waitFor { (field("player") as? ExoPlayer)?.mediaItemCount==2 }
        ui { activity!!.moveTaskToBack(true) }
        waitFor { field("player")==null }
        assertNull(ui { field("queuedNext") })
    }
    private fun skipNext() {
        MainActivity::class.java.getDeclaredMethod("skipEpisode",Int::class.javaPrimitiveType)
            .apply { isAccessible=true }.invoke(activity,1)
    }
    @Test fun manualNextUsesTheQueuedPlayerWithoutResettingIt() {
        val player=start(); waitFor { player.mediaItemCount==2 }
        Thread.sleep(800)
        val idle=AtomicInteger(); val buffering=AtomicInteger()
        val firstNextFrame=java.util.concurrent.atomic.AtomicLong(0)
        val oldPts=java.util.concurrent.atomic.AtomicLong(-1)
        ui {
            (field("sleepTimer") as SleepTimer).afterEpisodes(3)
            player.addListener(object: Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if(state==Player.STATE_IDLE) idle.incrementAndGet()
                    if(state==Player.STATE_BUFFERING) buffering.incrementAndGet()
                }
            })
            player.setVideoFrameMetadataListener { pts,_,_,_ ->
                val previous=oldPts.getAndSet(pts)
                if(previous>pts+1_000_000) firstNextFrame.compareAndSet(0,android.os.SystemClock.elapsedRealtime())
            }
        }
        Thread.sleep(200)
        val started=android.os.SystemClock.elapsedRealtime()
        val saved=ui {
            skipNext()
            (field("library") as Library).snapshot().history.single { it.series.id=="1000" }
        }
        waitFor { field("episodeIndex")==1 && player.playbackState==Player.STATE_READY && firstNextFrame.get()>0 }
        assertEquals("Prepared manual next must not stop or clear the player",0,idle.get())
        assertSame(player,ui { field("player") })
        assertTrue("Prepared next should render before the loading-indicator delay",firstNextFrame.get()-started<700)
        assertEquals(0,saved.episodeIndex); assertFalse(saved.completed); assertTrue(saved.position>0)
        assertEquals("再播完 3 集",ui { (field("sleepTimer") as SleepTimer).label() })
        android.util.Log.i("AutoplayTest","Manual prepared next: idleEvents=${idle.get()} bufferingEvents=${buffering.get()} firstFrameMs=${firstNextFrame.get()-started}")
    }
    @Test fun nextIsPreparedEarlyDuringALongEpisode() {
        val player=start(longCurrent=true)
        waitFor(12000) { player.mediaItemCount==2 }
        assertTrue(ui { player.duration-player.currentPosition>60000 })
        assertEquals(3_000_000L,ui { player.preloadConfiguration.targetPreloadDurationUs })
        Thread.sleep(800)
        ui {
            player.addListener(object: Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if(state==Player.STATE_READY) android.util.Log.i("AutoplayTest",
                        "Long manual READY: emptyTracks=${player.currentTracks.isEmpty} videoSelected=${player.currentTracks.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_VIDEO)}")
                }
            })
            skipNext()
        }
        waitFor { field("episodeIndex")==1 && player.isPlaying }
        assertSame(player,ui { field("player") })
        assertFalse(ui { field("playError") as Boolean })
    }
    @Test fun rapidManualNextDoesNotReturnToTheOldQueuedEpisode() {
        val player=start(); waitFor { player.mediaItemCount==2 }
        ui { skipNext(); skipNext() }
        waitFor { field("episodeIndex")==2 && player.currentMediaItem?.mediaId=="1003" && player.isPlaying }
        Thread.sleep(700)
        assertEquals(2,ui { field("episodeIndex") }); assertNull(ui { player.playerError })
        assertTrue(ui { player.mediaItemCount<=2 })
    }
    @Test fun immediateManualNextFallsBackWhenNothingIsPrepared() {
        val player=start()
        assertNull(ui { field("queuedNext") })
        ui { skipNext() }
        waitFor { field("episodeIndex")==1 && player.currentMediaItem?.mediaId=="1002" && player.isPlaying }
        assertNull(ui { player.playerError })
    }
    @Test fun speculativeSamplesRespectTheSharedAllocatorTarget() {
        val load=TvPlaybackBuffer.create(); val player=androidx.media3.exoplayer.analytics.PlayerId("instrumentation-budget")
        load.onPrepared(player)
        val allocator=load.getAllocator(player)
        val period=androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(Any())
        assertTrue(load.shouldContinuePreloading(player,androidx.media3.common.Timeline.EMPTY,period,0))
        val blocks=List(TvPlaybackBuffer.TARGET_BYTES/allocator.individualAllocationLength) { allocator.allocate() }
        try {
            assertFalse("Speculative samples must stop at the shared TV memory target",
                load.shouldContinuePreloading(player,androidx.media3.common.Timeline.EMPTY,period,0))
        } finally { blocks.forEach { allocator.release(it) }; load.onReleased(player) }
    }
    @Test fun introIsSkippedOnceAndManualSeekBackRemainsAllowed() {
        val player=start(longCurrent=true,introSeconds=8)
        val started=playbackRequestedAt // Exclude Activity cold-start time from the playback deadline.
        waitFor(3500) { player.currentPosition>=8000 }
        assertTrue("Intro policy should reach 8s before natural playback could",android.os.SystemClock.elapsedRealtime()-started<6500)
        ui { player.pause();player.seekTo(0);player.play() }
        waitFor { player.isPlaying }
        Thread.sleep(1400)
        assertTrue("A manual backward seek must not trigger the intro skip again",ui { player.currentPosition in 300..3500 })
        assertEquals(0,ui { field("episodeIndex") })
        assertNull(ui { player.playerError })
        android.util.Log.i("FeaturePlaybackTest","Intro skipped once; manual backward seek remained available")
    }

    @Test fun outroSkipHonorsStopAfterCurrentWithAnAlreadyQueuedSuccessor() {
        val player=start(longCurrent=true,outroSeconds=8)
        waitFor(15000) { player.mediaItemCount==2 }
        val outroSeeks=AtomicInteger()
        ui {
            player.addListener(object:Player.Listener {
                override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int) {
                    if(reason==Player.DISCONTINUITY_REASON_SEEK && newPosition.mediaItem?.mediaId=="1001" && newPosition.positionMs>=player.duration-200) outroSeeks.incrementAndGet()
                }
            })
            (field("sleepTimer") as SleepTimer).afterEpisodes(1)
            player.seekTo(player.duration-6000)
        }
        // Keep the policy response deadline separate from software decoding at the end seek.
        // The seek must be issued promptly; emitting STATE_ENDED can take longer on Goldfish.
        waitFor(6500) { outroSeeks.get()>0 }
        waitFor(20000) { field("sleepStopped")==true }
        assertEquals(0,ui { field("episodeIndex") })
        assertEquals("1001",ui { player.currentMediaItem?.mediaId })
        assertTrue("The outro policy must perform its end seek",outroSeeks.get()>0)
        assertFalse(ui { player.playWhenReady })
        assertNull(ui { field("queuedNext") })
        assertNull(ui { player.playerError })
        android.util.Log.i("FeaturePlaybackTest","Outro skip stopped on current episode; prepared successor did not advance")
    }

    @Test fun holdingRightTemporarilyChangesSpeedWithoutASeekJump() {
        val player=start(longCurrent=true)
        waitFor { player.isPlaying }
        val original=ui { player.playbackParameters.speed }
        val before=ui { player.currentPosition }
        val down=android.os.SystemClock.uptimeMillis()
        ui { activity!!.dispatchKeyEvent(android.view.KeyEvent(down,down,android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_RIGHT,0)) }
        waitFor(1800) { player.playbackParameters.speed==3f }
        Thread.sleep(300)
        ui { activity!!.dispatchKeyEvent(android.view.KeyEvent(down,android.os.SystemClock.uptimeMillis(),android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_DPAD_RIGHT,0)) }
        waitFor(1500) { player.playbackParameters.speed==original }
        assertTrue("Holding right must not also issue the ordinary 10-second seek",ui { player.currentPosition-before in 0..4500 })
        assertNull(ui { player.playerError })
        android.util.Log.i("FeaturePlaybackTest","Hold-right speed 3x restored to $original without seek jump")
    }

}
