package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hongguotv.core.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class MainCompletionIntegrationTest {
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun relatedRowsExposePartialStatusAndManualSearchMatchesWithReachableLoadMore() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            var page = 0
            var selected: RelatedWork? = null
            val episode = TextView(activity.get()).apply { id = View.generateViewId() }
            val work = RelatedWork(Series("200", "归来 第二季"), "第2季", false, confirmed = false)
            val strip = RelatedWorksView(activity.get(), listOf(episode), { _, _ -> }, { "预约" },
                { next, _ -> selected = next }, {}, { page = it })
            strip.show(RelatedWorksPage(listOf(work), 2, false, "仍有更多结果"))
            activity.get().setContentView(strip)
            val all = views(strip)
            assertTrue(all.filterIsInstance<TextView>().any { it.text.toString().contains("仍有更多") })
            assertTrue(all.filterIsInstance<TextView>().any { it.text.toString().contains("未确认") })
            all.single { it.tag == "200" }.performClick()
            assertEquals(work, selected)
            val more = all.single { it.tag == "related-load-more" }
            assertTrue(more.isFocusable)
            more.performClick()
            assertEquals(2, page)
            strip.show(RelatedWorksPage(listOf(work), null, true, "搜索已读完"))
            assertFalse(views(strip).any { it.tag == "related-load-more" })
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun mainOwnsAppearanceBackupRateAndSkipPolicyWithoutCreatingAPlayer() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            assertNotNull(field(activity, "appearance").get(activity))
            assertNotNull(field(activity, "backupController").get(activity))
            assertNotNull(field(activity, "incompatibleSkips").get(activity))
            val repository = field(activity, "repository").get(activity) as ContentRepository
            assertTrue(repository.http.networkInterceptors.any { it is ReceiveRateInterceptor })
            assertNull(field(activity, "player").get(activity))
            assertFalse((field(activity, "featureTools").get(activity) as FeatureTools).preferences.autoSkipIncompatible)
        } finally { controller.destroy() }
    }

    @Test fun automaticSkipRejectsNetworkFailuresBackgroundAndSleepStoppedState() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val skip = MainActivity::class.java.getDeclaredMethod("autoSkipIncompatible", Throwable::class.java).apply { isAccessible = true }
            field(activity, "detail").set(activity, Detail(Series("100", "测试"), listOf("1", "2")))
            field(activity, "screen").set(activity, "player")
            val preferences = (field(activity, "featureTools").get(activity) as FeatureTools).preferences
            preferences.autoSkipIncompatible = true
            assertEquals(false, skip.invoke(activity, java.io.IOException("offline")))
            assertEquals(false, skip.invoke(activity, IncompatibleStreamException()))
            field(activity, "foreground").set(activity, true)
            assertEquals(false, skip.invoke(activity, java.io.IOException("authentication failure")))
            val sleep = field(activity, "sleepTimer").get(activity) as SleepTimer
            sleep.afterEpisodes(1)
            assertEquals(false, skip.invoke(activity, IncompatibleStreamException()))
            assertEquals(1, sleep.episodesLeft)
            sleep.cancel()
            field(activity, "sleepStopped").set(activity, true)
            assertEquals(false, skip.invoke(activity, IncompatibleStreamException()))
            assertEquals(0, field(activity, "episodeIndex").get(activity))
        } finally { controller.destroy() }
    }

    @Test fun actualMainCodecFailureChainStopsAfterFiveAndManualSkipStartsANewBudget() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val library = field(activity, "library").get(activity) as Library
            (field(library, "loader").get(library) as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            val account = field(activity, "cloudAccount").get(activity) as AccountController
            (field(account, "worker").get(account) as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            var requests = 0
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                val body = okio.Buffer()
                chain.request().body!!.writeTo(body)
                val id = JSONObject(body.readUtf8()).getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0)
                val raw = """{"data":{"$id":{"video_model":{"video_list":[{"main_url":"https://example.com/video","video_meta":{"codec_type":"bvc2","width":720,"height":1280},"encrypt_info":{"encrypt":false}}]}}}}"""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(raw.toResponseBody()).build()
            }.build()
            field(activity, "repository").set(activity, ContentRepository(http))
            field(activity, "detail").set(activity, Detail(Series("100", "不兼容测试"), (1..8).map { it.toString() }))
            field(activity, "foreground").set(activity, true)
            (field(activity, "featureTools").get(activity) as FeatureTools).preferences.autoSkipIncompatible = true
            val play = MainActivity::class.java.getDeclaredMethod("playEpisode", Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            play.invoke(activity, 0, 0L, true, false, false)
            repeat(6) {
                (field(activity, "workJob").get(activity) as Future<*>).get(5, TimeUnit.SECONDS)
                ShadowLooper.idleMainLooper()
            }
            assertEquals(6, requests)
            assertEquals(5, field(activity, "episodeIndex").get(activity))
            assertEquals(5, (field(activity, "incompatibleSkips").get(activity) as IncompatibleSkipBudget).count)
            assertTrue(field(activity, "playError").get(activity) as Boolean)
            val controls = field(activity, "controls").get(activity) as View
            views(controls).filterIsInstance<TextView>().single { it.text == "跳过这集" }.performClick()
            assertEquals(6, field(activity, "episodeIndex").get(activity))
            assertEquals(0, (field(activity, "incompatibleSkips").get(activity) as IncompatibleSkipBudget).count)
        } finally { controller.destroy() }
    }

    @Test fun backAtHomeShowsExitConfirmationInsteadOfImmediatelyFinishing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            MainActivity::class.java.getDeclaredMethod("showExitConfirmation").apply { isAccessible = true }.invoke(activity)
            assertFalse(activity.isFinishing)
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            assertFalse(activity.isFinishing)
        } finally { controller.destroy() }
    }
}
