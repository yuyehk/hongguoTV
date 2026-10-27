package com.hongguotv.nativeapp

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hongguotv.core.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TvUsabilityTest {
    private fun get(target: Any, name: String) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun set(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun texts(view: View): List<TextView> =
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else if (view is TextView) listOf(view) else emptyList()

    @Test
    fun detailKeepsPlaybackCommentsAndSeparateSecondaryActions() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val library = get(activity, "library") as Library
            (get(library, "loader") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            val account = get(activity, "cloudAccount") as AccountController
            (get(account, "worker") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            set(
                activity,
                "repository",
                ContentRepository(
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(404)
                                .message("fixture")
                                .body("".toResponseBody())
                                .build()
                        }
                        .build()
                ),
            )
            val series = Series("1", "详情测试剧", description = "很长的简介。".repeat(50))
            val data = Detail(series, (1..40).map { "$it" })
            library.save(WatchProgress(series, "25", 24, 12000, 90000, false, 1000))
            set(activity, "detail", data)
            set(activity, "episodeIndex", 24)
            set(activity, "group", 1)
            MainActivity::class
                .java
                .getDeclaredMethod("showDetail", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(activity, false)
            val root = activity.findViewById<View>(android.R.id.content)
            val rows = texts(root)
            val play = rows.first { it.text == "继续第 25 集" }
            val comments = rows.first { it.text == "评论 · 第 25 集" }
            assertFalse(rows.any { it.text.toString().startsWith("选集 · 共") })
            assertSame(play.parent, comments.parent)
            assertEquals(comments.id, play.nextFocusRightId)
            assertEquals(play.id, comments.nextFocusLeftId)
            assertNotSame(play.parent, rows.first { it.text == "加入本机收藏" }.parent)
            assertEquals(2, rows.first { it.text.toString().startsWith("很长的简介") }.maxLines)
            assertEquals(1, get(activity, "group"))
            val selected = texts(root).first { it.text == "25\n当前" }
            assertTrue(selected.isSelected)
            assertTrue(rows.any { it.text == "跳转集数" })
            assertEquals("detail", get(activity, "screen"))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun defaultHomeKeepsLocalResumeAndOptionalFeaturesOff() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity=controller.get()
        try {
            val library=get(activity,"library") as Library
            (get(library,"loader") as ExecutorService).submit {}.get(5,TimeUnit.SECONDS)
            (get(get(activity,"cloudAccount")!!,"worker") as ExecutorService).submit {}.get(5,TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            val series=Series("7001","继续观看的剧")
            library.save(WatchProgress(series,"700101",0,45000,90000,false,1000))
            set(activity,"catalog",listOf(Series("7002","热门测试剧")))
            MainActivity::class.java.getDeclaredMethod("showCatalog",Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(activity,false,false,false)
            val rows=texts(activity.findViewById(android.R.id.content))
            assertTrue(rows.any { it.text.toString().contains("继续观看的剧") })
            assertFalse(rows.any { it.text=="账户" })
            assertTrue(rows.any { it.text=="登录" && it.visibility==android.view.View.VISIBLE })
            assertTrue(activity.findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.view.View>("account-avatar").isFocusable)
            assertTrue(rows.any { it.text=="预览" && it.isFocusable })
            assertFalse(rows.any { it.text=="设置" || it.text.toString().contains("版本与更新") })
            assertNull(get(activity,"homeFeed"))
            assertNull(get(activity,"player"))
            assertFalse(activity.getSharedPreferences("social",0).getBoolean("enabled",false))
            assertFalse(activity.getSharedPreferences("playback-controls",0).getBoolean("auto-resync",false))
            assertTrue(com.hongguotv.core.DeviceIdentity.isValid(activity.getSharedPreferences("device_identity",0).getString("device_id",null).orEmpty()))
        } finally { controller.destroy() }
    }
    @Test
    fun failedSearchKeepsPreviouslyLoadedRowsAndOffersRetry() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val activity=controller.get()
        try {
            val library=get(activity,"library") as Library
            (get(library,"loader") as ExecutorService).submit {}.get(5,TimeUnit.SECONDS)
            (get(get(activity,"cloudAccount")!!,"worker") as ExecutorService).submit {}.get(5,TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            val http=OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("fixture unavailable").body("".toResponseBody()).build()
            }.build()
            set(activity,"repository",ContentRepository(http))
            set(activity,"foreground",true)
            set(activity,"tab",1)
            set(activity,"query","失败重试测试")
            set(activity,"tvSearchOpen",true)
            set(activity,"catalog",listOf(Series("7003","上次成功结果")))
            MainActivity::class.java.getDeclaredMethod("showCatalog",Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(activity,true,false,false)
            (get(activity,"workJob") as java.util.concurrent.Future<*>).get(10,TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            val decor=activity.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY))
            decor.layout(0,0,960,540)
            ShadowLooper.idleMainLooper()
            @Suppress("UNCHECKED_CAST") val retained=get(activity,"catalog") as List<Series>
            assertEquals(listOf("7003"),retained.map { it.id })
            val rows=texts(activity.findViewById(android.R.id.content))
            assertTrue(rows.any { it.text=="上次成功结果" })
            assertTrue(rows.any { it.text=="重试" && it.isFocusable })
        } finally { controller.destroy() }
    }

}
