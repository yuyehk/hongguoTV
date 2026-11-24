// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hongguotv.core.Detail
import com.hongguotv.core.Series
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/**
 * Input regression only: the real playback screen is built, but media resolution never runs.
 * Speed-dialog selection, narrow-screen scrolling and cross-row DPAD navigation are covered by
 * Android instrumentation: LEGACY graphics cannot reproduce their window and text geometry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MobilePlaybackControlsTest {
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun get(target: Any, name: String): Any? = field(target, name).get(target)
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    /** Keeping work pending exercises controls during resolution without network or synthetic video. */
    private class PendingExecutor : AbstractExecutorService() {
        private val queued = mutableListOf<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); queued += command }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            queued.forEach { (it as? Future<*>)?.cancel(true) }
            return queued.toMutableList().also { queued.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
    }

    private inner class PlaybackScreen(autoplay: Boolean = true, touch: Boolean = true) : AutoCloseable {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity: MainActivity
        val player: ExoPlayer
        val root: View
        init {
            Instrumentation().setInTouchMode(touch)
            controller.create()
            activity = controller.get()
            (get(activity, "io") as ExecutorService).shutdownNow()
            field(activity, "io").set(activity, PendingExecutor())
            val library = get(activity, "library") as Library
            (get(library, "loader") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            val account = get(activity, "cloudAccount") as AccountController
            (get(account, "worker") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            field(activity, "detail").set(activity, Detail(Series("100", "触摸回归"), listOf("101", "102", "103")))
            field(activity, "foreground").set(activity, true)
            MainActivity::class.java.getDeclaredMethod("playEpisode", Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, 0, 0L, autoplay, false, false)
            // An unprepared ExoPlayer exposes real play intent without opening a media URI.
            player = ExoPlayer.Builder(activity).build().apply {
                setMediaItem(MediaItem.Builder().setMediaId("101").setUri("https://example.test/not-opened").build())
                playWhenReady = autoplay
            }
            field(activity, "player").set(activity, player)
            (get(activity, "playerView") as PlayerView).player = player
            root = get(activity, "root") as View
            // Do not start/resume: that would trigger the real startup updater and catalog traffic.
            controller.visible()
            layout()
        }
        val hud get() = get(activity, "hud") as View
        val controls get() = get(activity, "controls") as View
        fun layout() {
            val metrics = activity.resources.displayMetrics
            this@MobilePlaybackControlsTest.layout(activity.window.decorView, metrics.widthPixels, metrics.heightPixels)
        }
        fun surfaceTap() { tapAt(root, root.width / 2f, 8f); layout() }
        fun button(label: String) = views(controls).filterIsInstance<TextView>().single { it.text.toString() == label }
        fun key(code: Int) {
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            layout()
        }
        override fun close() {
            field(activity, "foreground").set(activity, false)
            controller.destroy()
            ShadowLooper.idleMainLooper()
        }
    }

    private fun layout(root: View, width: Int, height: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        ShadowLooper.idleMainLooper()
    }

    private fun motion(root: View, action: Int, down: Long, at: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, at, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { root.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun tapAt(root: View, x: Float, y: Float) {
        assertTrue("Regression must exercise Android touch mode, not focused remote clicks", root.isInTouchMode)
        val now = SystemClock.uptimeMillis()
        motion(root, MotionEvent.ACTION_DOWN, now, now, x, y)
        motion(root, MotionEvent.ACTION_UP, now, now + 40, x, y)
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
    }

    private fun tap(root: View, target: View) {
        val visible = Rect()
        assertTrue("Touch target must be visible: ${(target as? TextView)?.text}", target.getGlobalVisibleRect(visible))
        assertTrue("Touch target must have an area", visible.width() > 0 && visible.height() > 0)
        val origin = IntArray(2).also(root::getLocationOnScreen)
        tapAt(root, visible.exactCenterX() - origin[0], visible.exactCenterY() - origin[1])
    }

    @Test fun aSingleSurfaceTapOpensAndClosesControlsWithoutChangingPlayback() {
        PlaybackScreen().use { screen ->
            assertEquals(View.GONE, screen.hud.visibility)
            assertFalse((get(screen.activity, "playerView") as PlayerView).useController)
            screen.surfaceTap()
            assertEquals(View.VISIBLE, screen.hud.visibility)
            assertEquals(View.VISIBLE, screen.controls.visibility)
            assertTrue(get(screen.activity, "panel") as Boolean)
            screen.surfaceTap()
            assertEquals(View.GONE, screen.hud.visibility)
            assertFalse(get(screen.activity, "panel") as Boolean)
            assertSame(screen.player, get(screen.activity, "player"))
            assertEquals("101", screen.player.currentMediaItem?.mediaId)
            assertTrue(screen.player.playWhenReady)
            assertTrue(get(screen.activity, "requestedAutoplay") as Boolean)
        }
    }

    @Test fun firstTouchOpensEpisodePickerAndClosingItKeepsPausedIntent() {
        PlaybackScreen(autoplay = false).use { screen ->
            screen.surfaceTap()
            val episodes = screen.button("选集")
            assertFalse("An unfocused button must activate on its first touch", episodes.isFocused)
            tap(screen.root, episodes)
            assertNotNull(get(screen.activity, "episodePanel"))
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            val decor = dialog.window!!.decorView
            layout(decor, 500, 320)
            tap(decor, views(decor).filterIsInstance<TextView>().single { it.text.toString() == "关闭" })
            assertNull(get(screen.activity, "episodePanel"))
            assertFalse(screen.player.playWhenReady)
            assertFalse(get(screen.activity, "requestedAutoplay") as Boolean)
            assertEquals(0, get(screen.activity, "episodeIndex"))
        }
    }

    @Test fun firstTouchOnAnUnfocusedEpisodeNumberSelectsIt() {
        Instrumentation().setInTouchMode(true)
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var selected: Int? = null
        val panel = EpisodePanel(controller.get(), 24, 0, { _, _, _ -> }, { selected = it }, {})
        try {
            panel.show()
            val decor = ShadowDialog.getLatestDialog().window!!.decorView
            layout(decor, 500, 320)
            val second = views(decor).single { it.contentDescription == "第 2 集" }
            assertFalse(second.isFocused)
            tap(decor, second)
            assertEquals(1, selected)
        } finally { panel.dismiss(); controller.pause().stop().destroy() }
    }

    @Test fun remoteUpDownAndBackKeepTheirOriginalActionsAfterTouchSupport() {
        PlaybackScreen(touch = false).use { screen ->
            screen.key(KeyEvent.KEYCODE_DPAD_UP)
            assertEquals(View.VISIBLE, screen.controls.visibility)
            assertTrue(screen.button("选集").isFocusable)
            screen.key(KeyEvent.KEYCODE_BACK)
            assertEquals(View.GONE, screen.hud.visibility)
            assertEquals("player", get(screen.activity, "screen"))
            screen.key(KeyEvent.KEYCODE_DPAD_DOWN)
            assertNotNull(get(screen.activity, "episodePanel"))
            assertFalse(screen.player.playWhenReady)
            val dialog = ShadowDialog.getLatestDialog()
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            ShadowLooper.idleMainLooper()
            assertNull(get(screen.activity, "episodePanel"))
            assertTrue(screen.player.playWhenReady)
            assertEquals(0, get(screen.activity, "episodeIndex"))
            assertEquals("player", get(screen.activity, "screen"))
        }
    }

}
