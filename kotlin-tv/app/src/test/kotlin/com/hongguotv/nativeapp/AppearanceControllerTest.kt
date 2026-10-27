package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.widget.FrameLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class AppearanceControllerTest {
    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it); bitmap.recycle() }.toByteArray()
    }
    private fun flush(appearance: AppearanceController) {
        val f = appearance.javaClass.getDeclaredField("worker").apply { isAccessible = true }
        (f.get(appearance) as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper()
    }
    @Test fun allTwentyPalettesHaveReadableTextAndLightStylesAreLight() {
        assertEquals(20, FeatureTheme.entries.size)
        FeatureTheme.entries.forEach { theme ->
            TvStyle.apply(theme)
            assertTrue("$theme primary", contrast(TvStyle.text, TvStyle.surface) >= 4.5)
            assertTrue("$theme muted", contrast(TvStyle.muted, TvStyle.surface) >= 4.5)
            assertTrue("$theme focus", contrast(TvStyle.text, TvStyle.raised) >= 4.5)
        }
        TvStyle.apply(FeatureTheme.LIGHT)
        assertTrue(Color.red(TvStyle.background) > 220)
        TvStyle.apply(FeatureTheme.DARK)
    }
    private fun contrast(a: Int, b: Int): Double {
        fun luminance(c: Int): Double = listOf(Color.red(c), Color.green(c), Color.blue(c)).map {
            val v = it / 255.0
            if (v <= .04045) v / 12.92 else Math.pow((v + .055) / 1.055, 2.4)
        }.let { it[0] * .2126 + it[1] * .7152 + it[2] * .0722 }
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
    @Test fun localImportRoundTripsAndInvalidBackupDoesNotChangeThemeOrImage() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val a = controller.get()
        var changed = 0
        val appearance = AppearanceController(a, { changed++ }, {})
        try {
            val file = File(a.cacheDir, "appearance-test.jpg").apply { writeBytes(jpeg()) }
            a.getSharedPreferences("native-library-v1", 0).edit().putString("fixture", "keep").commit()
            appearance.onImagePicked(Uri.fromFile(file))
            flush(appearance)
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().orEmpty().toString(), changed > 0)
            val state = appearance.exportState()
            assertTrue(JSONObject(state).getString("image").isNotEmpty())
            AppearanceController.validateState(state)
            val root = FrameLayout(a)
            appearance.apply(root)
            assertNotNull(root.background)
            val malformed = JSONObject(state).put("theme", "LIGHT").put("image", "not-jpeg").toString()
            assertTrue(runCatching { appearance.restoreState(malformed) }.isFailure)
            assertEquals(state, appearance.exportState())
            assertEquals("keep", a.getSharedPreferences("native-library-v1", 0).getString("fixture", null))
            appearance.restoreState(JSONObject().put("version", 1).put("theme", "LIGHT").put("image", "").toString())
            flush(appearance)
            assertEquals("LIGHT", JSONObject(appearance.exportState()).getString("theme"))
            assertEquals("", JSONObject(appearance.exportState()).getString("image"))
        } finally { appearance.destroy(); controller.pause().stop().destroy() }
    }
    @Test fun chooserAndClosingDialogHaveNoPersistentSideEffects() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var choices = 0
        val appearance = AppearanceController(controller.get(), {}, { choices++ })
        try {
            val before = appearance.exportState()
            appearance.show()
            val d = ShadowDialog.getLatestDialog()
            assertTrue(d.isShowing)
            appearance.close()
            assertFalse(d.isShowing)
            assertEquals(before, appearance.exportState())
            assertEquals(0, choices)
        } finally { appearance.destroy(); controller.pause().stop().destroy() }
    }
    @Test fun invalidImageIsRejectedAndInputReadIsBounded() {
        assertTrue(runCatching { AppearanceImages.prepare(ByteArray(100)) }.isFailure)
        assertTrue(runCatching { AppearanceImages.readBounded(java.io.ByteArrayInputStream(ByteArray(AppearanceImages.MAX_UPLOAD + 1))) }.isFailure)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        assertTrue(runCatching { AppearanceImages.prepare(png) }.isFailure)
    }
    @Test fun closingDuringQueuedImportPreventsPersistentImageWrite() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val appearance = AppearanceController(controller.get(), {}, {})
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            val before = appearance.exportState()
            val worker = appearance.javaClass.getDeclaredField("worker").apply { isAccessible = true }.get(appearance) as ExecutorService
            worker.submit { gate.await(5, TimeUnit.SECONDS) }
            val file = File(controller.get().cacheDir, "cancel-image.jpg").apply { writeBytes(jpeg()) }
            appearance.onImagePicked(Uri.fromFile(file))
            appearance.close()
            gate.countDown()
            flush(appearance)
            assertEquals(before, appearance.exportState())
        } finally { gate.countDown(); appearance.destroy(); controller.pause().stop().destroy() }
    }
    @Test fun openingAppearanceWhileBackgroundLoadsDoesNotDiscardSavedImage() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val a = controller.get()
        val first = AppearanceController(a, {}, {})
        first.restoreState(JSONObject().put("version", 1).put("theme", "DARK")
            .put("image", android.util.Base64.encodeToString(jpeg(), android.util.Base64.NO_WRAP)).toString())
        first.destroy()
        val reopened = AppearanceController(a, {}, {})
        try {
            reopened.loadAsync()
            reopened.show()
            flush(reopened)
            val root = FrameLayout(a)
            reopened.apply(root)
            assertNotNull(root.background)
        } finally { reopened.destroy(); controller.pause().stop().destroy() }
    }
}
