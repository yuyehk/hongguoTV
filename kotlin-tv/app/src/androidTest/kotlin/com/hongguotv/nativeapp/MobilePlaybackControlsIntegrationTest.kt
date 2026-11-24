// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Dialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.PlaybackSpeed
import com.hongguotv.core.SleepTimer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in real touch/native-media check: -e mobilePlaybackControls true.
 * Requires the isolated debug package below; never run against the user's installed package.
 * Only the initial episode launch uses reflection. Every menu/selection interaction injects
 * actual touchscreen DOWN/UP/MOVE events. No performClick, mock repository or menu invocation.
 * This checks interaction and short native playback, not natural full-episode autoplay.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MobilePlaybackControlsIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var activity: MainActivity? = null
    private val events = JSONArray()
    private val startedAt = SystemClock.elapsedRealtime()
    private var horizontalSwipes = 0

    private fun <T> ui(action: () -> T): T {
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { result.set(action()) } catch (failure: Throwable) { error.set(failure) } }
        error.get()?.let { throw it }
        return result.get()
    }

    private fun read(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun field(name: String): Any? = read(activity!!, name)
    private fun player(): ExoPlayer = field("player") as ExoPlayer
    private fun controls(): View = field("controls") as View
    private fun episodeDialog(): Dialog? = field("episodePanel")?.let { read(it, "dialog") as Dialog }
    private fun speedDialog(): android.app.AlertDialog? = field("speedDialog") as? android.app.AlertDialog
    private fun toolsDialog(): Dialog? = read(field("tvTools")!!, "dialog") as? Dialog

    private fun event(name: String, vararg values: Pair<String, Any>) {
        val item = JSONObject().put("event", name).put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
        values.forEach { (key, value) -> item.put(key, value) }
        events.put(item)
        Log.i(TAG, item.toString())
    }

    private fun waitFor(message: String, timeout: Long = 8000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ui(condition)) return
            Thread.sleep(50)
        }
        fail(message)
    }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
    }

    private fun text(root: View, expected: String, prefix: Boolean = false): View? = descendants(root)
        .firstOrNull { it is TextView && it.isShown && if (prefix) it.text.toString().startsWith(expected) else it.text.toString() == expected }

    private data class TouchBounds(val screen: Rect, val window: Rect, val offsetX: Int, val offsetY: Int)

    /** getGlobalVisibleRect is window-root relative; input injection uses absolute screen pixels. */
    private fun touchBounds(view: View): TouchBounds? {
        val clipped = Rect()
        if (!view.isShown || !view.getGlobalVisibleRect(clipped) || clipped.width() <= 4 || clipped.height() <= 4) return null
        val inWindow = IntArray(2).also(view::getLocationInWindow)
        val onScreen = IntArray(2).also(view::getLocationOnScreen)
        val offsetX = onScreen[0] - inWindow[0]
        val offsetY = onScreen[1] - inWindow[1]
        return TouchBounds(Rect(clipped).apply { offset(offsetX, offsetY) }, clipped, offsetX, offsetY)
    }

    private fun visible(view: View): Rect? = touchBounds(view)?.screen

    private fun tapEvidence(label: String, bounds: TouchBounds, x: Float, y: Float) {
        event("single-tap", "target" to label, "screenX" to x, "screenY" to y,
            "windowOffsetX" to bounds.offsetX, "windowOffsetY" to bounds.offsetY,
            "windowVisibleRect" to bounds.window.toShortString(), "screenVisibleRect" to bounds.screen.toShortString())
    }

    private fun motion(downAt: Long, action: Int, x: Float, y: Float) {
        val input = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0)
        input.source = InputDevice.SOURCE_TOUCHSCREEN
        try { instrumentation.sendPointerSync(input) } finally { input.recycle() }
    }

    private fun tap(view: View, label: String) {
        val bounds = ui { touchBounds(view) ?: error("Touch target is not visible: $label") }
        val rect = bounds.screen
        val down = SystemClock.uptimeMillis()
        motion(down, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
        Thread.sleep(60)
        motion(down, MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY())
        instrumentation.waitForIdleSync()
        tapEvidence(label, bounds, rect.exactCenterX(), rect.exactCenterY())
    }

    private fun swipe(view: View, horizontal: Boolean, forward: Boolean) {
        val bounds = ui { touchBounds(view) ?: error("Swipe viewport is not visible") }
        val rect = bounds.screen
        val from = if (forward) .82f else .18f
        val to = if (forward) .18f else .82f
        fun x(part: Float) = if (horizontal) rect.left + rect.width() * part else rect.exactCenterX()
        fun y(part: Float) = if (horizontal) rect.exactCenterY() else rect.top + rect.height() * part
        val down = SystemClock.uptimeMillis()
        motion(down, MotionEvent.ACTION_DOWN, x(from), y(from))
        for (step in 1..12) {
            Thread.sleep(25)
            val part = from + (to - from) * step / 12
            motion(down, MotionEvent.ACTION_MOVE, x(part), y(part))
        }
        motion(down, MotionEvent.ACTION_UP, x(to), y(to))
        instrumentation.waitForIdleSync()
        Thread.sleep(200)
        if (horizontal) horizontalSwipes++
        event("swipe", "horizontal" to horizontal, "fromScreenX" to x(from), "fromScreenY" to y(from),
            "toScreenX" to x(to), "toScreenY" to y(to), "windowOffsetX" to bounds.offsetX,
            "windowOffsetY" to bounds.offsetY)
    }

    private fun revealHorizontal(target: View) {
        repeat(8) {
            val action = ui {
                val parents = generateSequence(target.parent) { it.parent }
                val scroll = parents.filterIsInstance<HorizontalScrollView>().firstOrNull()
                val full = IntArray(2).also(target::getLocationOnScreen)
                val rect = visible(target)
                if (rect != null && rect.width() >= target.width - 2) null
                else {
                    check(scroll != null) { "Clipped control has no horizontal scroll container" }
                    val viewport = visible(scroll) ?: error("Horizontal controls are off-screen")
                    scroll to (full[0] + target.width / 2 >= viewport.centerX())
                }
            } ?: return
            swipe(action.first, horizontal = true, forward = action.second)
        }
        fail("Touch swipes could not reveal the complete control")
    }

    private fun tapControl(label: String, prefix: Boolean = false) {
        val target = ui { text(controls(), label, prefix) ?: error("Control is missing: $label") }
        revealHorizontal(target)
        tap(target, label)
    }

    private fun showMenuWithOneTap() {
        val video = ui { field("playerView") as View }
        // Tap the upper video area so an existing bottom HUD cannot receive the event instead.
        val bounds = ui { touchBounds(video) ?: error("Native video surface is not visible") }
        val rect = bounds.screen
        val down = SystemClock.uptimeMillis()
        val y = rect.top + rect.height() * .20f
        motion(down, MotionEvent.ACTION_DOWN, rect.exactCenterX(), y)
        Thread.sleep(60)
        motion(down, MotionEvent.ACTION_UP, rect.exactCenterX(), y)
        instrumentation.waitForIdleSync()
        tapEvidence("native-video", bounds, rect.exactCenterX(), y)
        waitFor("One video tap did not reveal playback controls", 3000) { controls().isShown && field("panel") == true }
        assertTrue(ui { text(controls(), "选集") != null && text(controls(), "倍速 ", true) != null })
    }

    private fun ensureMenu() {
        if (!ui { controls().isShown && field("panel") == true }) showMenuWithOneTap()
    }

    private fun chooseSpeed(speed: Float) {
        ensureMenu()
        tapControl("倍速 ", prefix = true)
        waitFor("A single speed-button tap did not open its dialog", 3000) { speedDialog()?.isShowing == true }
        val label = PlaybackSpeed.label(speed)
        var item: View? = null
        repeat(4) {
            if (item == null) {
                item = ui { text(speedDialog()!!.window!!.decorView, label)?.takeIf { visible(it) != null } }
                if (item == null) {
                    val list = ui { speedDialog()!!.listView }
                    swipe(list, horizontal = false, forward = speed >= 1.5f)
                }
            }
        }
        tap(checkNotNull(item) { "Requested speed is not touch-visible" }, label)
        waitFor("Speed selection did not affect both player and saved preference") {
            speedDialog() == null && player().playbackParameters.speed == speed &&
                (field("library") as Library).playbackSpeed == speed &&
                instrumentation.targetContext.getSharedPreferences("native-library-v1", 0).getFloat("playbackSpeed", 1f) == speed
        }
        event("speed-verified", "playerSpeed" to speed, "persistedSpeed" to speed)
    }

    private fun openEpisodes() {
        ensureMenu()
        tapControl("选集")
        waitFor("A single episode-button tap did not open the panel", 3000) { episodeDialog()?.isShowing == true }
    }

    private fun closeEpisodes() {
        val close = ui { text(episodeDialog()!!.window!!.decorView, "关闭") ?: error("Episode close control missing") }
        tap(close, "关闭选集")
        waitFor("A single close tap did not dismiss the episode panel", 3000) { episodeDialog() == null }
    }

    private fun inside(view: View?, container: View): Boolean =
        generateSequence(view) { it.parent as? View }.any { it === container }

    private fun fullyVisible(view: View): Boolean = visible(view)?.let {
        it.width() >= view.width - 2 && it.height() >= view.height - 2
    } ?: false

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
        Thread.sleep(150)
        val focus = ui {
            val view = activity!!.currentFocus
            (view as? TextView)?.text?.toString().orEmpty() to (view?.let(::fullyVisible) ?: false)
        }
        event("key-input", "key" to KeyEvent.keyCodeToString(code),
            "focusedLabel" to focus.first, "focusedFullyVisible" to focus.second)
    }

    /** Real DPAD input must preserve navigation and scroll the focused controls into view. */
    private fun verifyDpadNavigation() {
        ensureMenu()
        tapControl("收起")
        waitFor("The collapse button did not hide playback controls", 3000) { field("panel") == false }
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor("DPAD_UP did not open the menu with transport play/pause focused", 3000) {
            field("panel") == true && controls().isShown &&
                activity!!.currentFocus === field("transportPlay") && fullyVisible(activity!!.currentFocus!!)
        }
        val transport = ui { (controls() as ViewGroup).getChildAt(0) }
        val options = ui { (controls() as ViewGroup).getChildAt(1) as HorizontalScrollView }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("DPAD_DOWN did not move focus from transport to options", 3000) {
            inside(activity!!.currentFocus, options)
        }
        val settings = ui { text(options, "播放设置") ?: error("DPAD target is missing") }
        var rightPresses = 0
        while (!ui { activity!!.currentFocus === settings } && rightPresses < 12) {
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            rightPresses++
        }
        waitFor("DPAD_RIGHT did not reach visible playback settings within 12 presses", 3000) {
            activity!!.currentFocus === settings && settings.isFocused && fullyVisible(settings)
        }
        val scrollEvidence = ui {
            val overflow = options.getChildAt(0).width > options.width
            if (overflow) assertTrue("DPAD focus must horizontally scroll overflowing options", options.scrollX > 0)
            options.scrollX to overflow
        }
        event("dpad-settings-reached", "rightPresses" to rightPresses,
            "optionsScrollX" to scrollEvidence.first, "optionsOverflow" to scrollEvidence.second)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("DPAD_CENTER did not activate playback settings", 3000) { toolsDialog()?.isShowing == true }
        assertTrue(ui { text(toolsDialog()!!.window!!.decorView, "倍速 ", true) != null })
        key(KeyEvent.KEYCODE_BACK)
        waitFor("BACK did not close settings and restore its original button focus", 3000) {
            toolsDialog() == null && activity!!.currentFocus === settings && fullyVisible(settings)
        }
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor("DPAD_UP did not return from options to the transport row", 3000) {
            inside(activity!!.currentFocus, transport) && activity!!.currentFocus?.let(::fullyVisible) == true
        }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("BACK did not collapse playback controls", 3000) { field("panel") == false && !controls().isShown }
        assertEquals("DPAD menu navigation must not change the selected episode", SECOND,
            ui { player().currentMediaItem?.mediaId })
        event("dpad-navigation-verified", "episodeId" to SECOND, "playWhenReady" to ui { player().playWhenReady })
    }

    @Test fun realTouchControlsChangeSpeedAndEpisode() {
        assumeTrue("Explicit live interaction opt-in is required",
            InstrumentationRegistry.getArguments().getString("mobilePlaybackControls") == "true")
        val context = instrumentation.targetContext
        assertEquals("Use only the isolated test package", PACKAGE, context.packageName)
        assertTrue(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertTrue("The isolated package must not contain user accounts",
            context.getSharedPreferences("cloud_account_secure", 0).all.isEmpty())
        var passed = false
        var settingsChanged = false
        var oldSpeed = 1f
        var oldAutoNext = true
        var oldNextSeason = false
        var oldAutoSkip = false
        var oldSkip = FeatureSkipMark()
        var restoredByTouch = false
        val preferences = FeaturePreferences(context)
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            waitFor("Local library did not load", 30000) { (field("library") as Library).isLoaded }
            val repository = ui {
                MainActivity::class.java.getDeclaredMethod("invalidatePage").apply { isAccessible = true }.invoke(activity)
                assertFalse((field("cloudAccount") as AccountController).signedIn)
                field("repository") as ContentRepository
            }
            val detail = repository.detail(SERIES)
            assertTrue(detail.series.title.contains("第七季") || detail.series.title.contains("第7季"))
            assertEquals(listOf(FIRST, SECOND), detail.episodes.take(2))
            ui {
                val library = field("library") as Library
                oldSpeed = library.playbackSpeed
                oldAutoNext = library.autoNext
                oldNextSeason = preferences.nextSeason
                oldAutoSkip = preferences.autoSkipIncompatible
                oldSkip = preferences.skip(SERIES)
                settingsChanged = true
                library.playbackSpeed = 1f
                library.autoNext = false // The separate full-episode test verifies automatic continuation.
                preferences.nextSeason = false
                preferences.autoSkipIncompatible = false
                preferences.setSkip(SERIES, 0, 0)
                (field("sleepTimer") as SleepTimer).cancel()
                MainActivity::class.java.getDeclaredField("detail").apply { isAccessible = true }.set(activity, detail)
                MainActivity::class.java.getDeclaredMethod("playEpisode", Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(activity, 0, 0L, true, false, false)
            }
            waitFor("Real first episode did not start native playback", 90000) {
                val p = field("player") as? ExoPlayer
                p?.currentMediaItem?.mediaId == FIRST && p.isPlaying && p.currentPosition >= 1500 &&
                    p.duration >= 60000 && (p.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
            }
            event("native-start", "episodeId" to FIRST, "widthDp" to context.resources.configuration.screenWidthDp,
                "heightDp" to context.resources.configuration.screenHeightDp)
            showMenuWithOneTap()
            chooseSpeed(1.5f)

            openEpisodes()
            waitFor("Episode picker did not pause playback") { !player().playWhenReady }
            closeEpisodes()
            waitFor("Canceling episode picker did not restore playing intent") { player().isPlaying }
            assertEquals(FIRST, ui { player().currentMediaItem?.mediaId })
            event("cancel-preserved", "intent" to "playing", "episodeId" to FIRST)

            ensureMenu()
            tapControl("暂停")
            waitFor("One pause tap did not pause") { !player().playWhenReady }
            openEpisodes()
            closeEpisodes()
            val pausedAt = ui { player().currentPosition }
            Thread.sleep(350)
            assertFalse("Canceling picker must retain paused intent", ui { player().playWhenReady })
            assertEquals(FIRST, ui { player().currentMediaItem?.mediaId })
            assertTrue("Paused clock advanced after picker cancellation", ui { player().currentPosition } - pausedAt < 200)
            event("cancel-preserved", "intent" to "paused", "episodeId" to FIRST)
            ensureMenu()
            tapControl("播放")
            waitFor("One play tap did not resume") { player().isPlaying }

            openEpisodes()
            val second = ui { descendants(episodeDialog()!!.window!!.decorView)
                .single { it.contentDescription?.toString() == "第 2 集" } }
            tap(second, "第 2 集")
            waitFor("One episode tap did not select and play the real second episode", 90000) {
                val p = field("player") as? ExoPlayer
                episodeDialog() == null && p?.currentMediaItem?.mediaId == SECOND && p.isPlaying && p.currentPosition >= 1500
            }
            assertEquals(1.5f, ui { player().playbackParameters.speed }, 0f)
            event("manual-episode-verified", "episodeId" to SECOND, "positionMs" to ui { player().currentPosition })

            showMenuWithOneTap()
            val beforeSettings = horizontalSwipes
            tapControl("播放设置")
            waitFor("Playback settings could not be opened by touch", 3000) { toolsDialog()?.isShowing == true }
            assertTrue(ui { text(toolsDialog()!!.window!!.decorView, "倍速 ", true) != null })
            val cancel = ui { text(toolsDialog()!!.window!!.decorView, "取消") ?: error("Settings cancel button missing") }
            tap(cancel, "取消播放设置")
            waitFor("Playback settings did not dismiss") { toolsDialog() == null }
            event("settings-accessible", "horizontalSwipes" to (horizontalSwipes - beforeSettings),
                "widthDp" to context.resources.configuration.screenWidthDp)
            if (InstrumentationRegistry.getArguments().getString("requireNarrow") == "true") {
                assertTrue("Narrow-screen verification requires width <= 600dp", context.resources.configuration.screenWidthDp <= 600)
                assertTrue("Narrow-screen options must be reached through a real horizontal gesture", horizontalSwipes > beforeSettings)
            }
            chooseSpeed(oldSpeed)
            restoredByTouch = true
            verifyDpadNavigation()
            passed = true
            event("PASS", "scope" to "Real touch and DPAD controls plus short native playback; not full-episode autoplay")
        } catch (failure: Throwable) {
            val safe = failure.message.orEmpty().replace(Regex("https?://\\S+"), "[URL]").take(250)
            event("FAIL", "type" to failure.javaClass.simpleName, "message" to safe)
            throw AssertionError("Mobile controls acceptance failed: ${failure.javaClass.simpleName}: $safe")
        } finally {
            ui {
                if (settingsChanged) {
                    (field("library") as Library).apply { playbackSpeed = oldSpeed; autoNext = oldAutoNext; flush() }
                    (field("player") as? ExoPlayer)?.setPlaybackSpeed(oldSpeed)
                    preferences.nextSeason = oldNextSeason
                    preferences.autoSkipIncompatible = oldAutoSkip
                    preferences.setSkip(SERIES, oldSkip.introSeconds, oldSkip.outroSeconds)
                }
                activity?.finish()
            }
            instrumentation.waitForIdleSync()
            context.filesDir.resolve("mobile-controls-verification.json").writeText(JSONObject()
                .put("passed", passed).put("package", PACKAGE).put("speedRestoredThroughTouch", restoredByTouch)
                .put("scope", "Isolated local touch interaction and short native playback; not physical device or full-episode acceptance")
                .put("events", events).toString(2))
        }
    }

    private companion object {
        const val TAG = "MobileControlsTest"
        const val PACKAGE = "com.hongguotv.nativeapp.localmobile01116"
        const val SERIES = "7707078673207151678"
        const val FIRST = "7707465250475346969"
        const val SECOND = "7707465250475363353"
    }
}
