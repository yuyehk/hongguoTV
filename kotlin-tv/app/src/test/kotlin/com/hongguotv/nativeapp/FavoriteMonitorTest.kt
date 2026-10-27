package com.hongguotv.nativeapp

import com.hongguotv.core.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class FavoriteMonitorTest {
    private val first=Series("100","归来 第一季")
    private fun library(favorites: List<Series> = listOf(first))=Library(RuntimeEnvironment.getApplication()).also { library ->
        val data=BackupData(favorites,emptyList(),emptySet(),emptyList(),BackupSettings())
        Library::class.java.getDeclaredField("index").apply { isAccessible=true }.set(library,LibraryIndex(data))
    }
    private fun source(answer: (Request)->String)=ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
        val request=chain.request()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
            .body(answer(request).toResponseBody()).build()
    }.build())
    private fun detail(id: String)="""<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"$id","series_title":"归来 第一季","vid_list":["101","102"]}}}};</script>"""
    private val related="""{"code":0,"data":[{"show_type":547,"video_data":[{"series_id":"200","title":"归来 第二季","content_type":1004,"episode_cnt":10,"season_index":2}]}]}"""
    private fun flush(monitor: FavoriteMonitor) {
        val worker=FavoriteMonitor::class.java.getDeclaredField("worker").apply { isAccessible=true }.get(monitor) as ExecutorService
        repeat(12) {
            worker.submit {}.get(5,TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            if(!monitor.running) return
        }
        fail("Serial monitor did not finish")
    }

    @Test fun noSeasonCallbackMeansNoOptionalRelatedRequests() {
        val paths=CopyOnWriteArrayList<String>()
        val library=library()
        val monitor=FavoriteMonitor(library,source { paths+=it.url.encodedPath; detail("100") }) {}
        try {
            monitor.check(true);flush(monitor)
            assertEquals(listOf("/detail"),paths)
            assertEquals(2,library.favoriteUpdate("100")!!.total)
        } finally { monitor.destroy();library.close() }
    }

    @Test fun releasedNextSeasonCallbackPreservesSuccessfulEpisodeCount() {
        val library=library()
        val notices=mutableListOf<Pair<Series,Series>>()
        val monitor=FavoriteMonitor(library,source { if(it.url.encodedPath=="/detail") detail("100") else related }) {}
        monitor.onNextSeason={current,next -> notices+=current to next}
        try {
            monitor.check(true);flush(monitor)
            assertEquals("100",""+notices.single().first.id)
            assertEquals("200",notices.single().second.id)
            assertEquals(2,library.favoriteUpdate("100")!!.total)
            assertFalse(monitor.status.contains("失败"))
        } finally { monitor.destroy();library.close() }
    }

    @Test fun failedRelatedLookupDoesNotInvalidateCountOrAddMonitorRetries() {
        val paths=CopyOnWriteArrayList<String>()
        val library=library()
        val monitor=FavoriteMonitor(library,source {
            paths+=it.url.encodedPath
            if(it.url.encodedPath=="/detail") detail("100") else throw IOException("offline")
        }) {}
        monitor.onNextSeason={_,_ -> fail("No season response was accepted")}
        try {
            monitor.check(true);flush(monitor)
            assertEquals(2,library.favoriteUpdate("100")!!.total)
            assertEquals(1,paths.count { it=="/detail" })
            assertEquals(3,paths.count { it=="/reading/bookapi/plan/v:version/" }) // repository's bounded host failover only
            assertFalse(monitor.status.contains("失败"))
        } finally { monitor.destroy();library.close() }
    }

    @Test fun stopBeforeMainDeliverySuppressesSeasonAndCountCallbacks() {
        val library=library()
        var callbacks=0
        val monitor=FavoriteMonitor(library,source { if(it.url.encodedPath=="/detail") detail("100") else related }) {}
        monitor.onNextSeason={_,_ -> callbacks++}
        try {
            monitor.check(true)
            val worker=FavoriteMonitor::class.java.getDeclaredField("worker").apply { isAccessible=true }.get(monitor) as ExecutorService
            worker.submit {}.get(5,TimeUnit.SECONDS)
            monitor.stop();ShadowLooper.idleMainLooper()
            assertEquals(0,callbacks)
            assertNull(library.favoriteUpdate("100"))
        } finally { monitor.destroy();library.close() }
    }

    @Test fun threeConsecutiveDetailFailuresStopBeforeFourthFavoriteAndDoNotReadRelated() {
        var calls=0
        val library=library((1..4).map { Series(it.toString(),"作品$it 第一季") })
        val monitor=FavoriteMonitor(library,source { calls++;throw IOException("offline") }) {}
        monitor.onNextSeason={_,_ -> fail("No details were accepted")}
        try {
            monitor.check(true);flush(monitor)
            assertEquals(3,calls)
            assertTrue(monitor.status.contains("3 部失败"))
        } finally { monitor.destroy();library.close() }
    }

    @Test fun ordinaryCheckUsesHourlyGateAndForceRefreshesRelatedMetadata() {
        val paths=CopyOnWriteArrayList<String>()
        val library=library()
        val monitor=FavoriteMonitor(library,source {
            paths+=it.url.encodedPath
            if(it.url.encodedPath=="/detail") detail("100") else related
        }) {}
        monitor.onNextSeason={_,_ -> }
        try {
            monitor.check(true);flush(monitor)
            monitor.check();flush(monitor)
            assertEquals(2,paths.size)
            monitor.check(true);flush(monitor)
            assertEquals(2,paths.count { it=="/reading/bookapi/plan/v:version/" })
        } finally { monitor.destroy();library.close() }
    }
}
