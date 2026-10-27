package com.hongguotv.nativeapp

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SocialPreferencesTest {
    @Test fun keywordsAreLiteralNormalizedAndBounded() {
        val words = SocialPolicy.keywords("剧透，ＡＢＣ\n.*\n剧透")
        assertEquals(listOf("剧透", "ABC", ".*"), words)
        assertTrue(SocialPolicy.blocked("aBc spoiler", words))
        assertTrue(SocialPolicy.blocked("contains .* literally", words))
        assertFalse(SocialPolicy.blocked("an ordinary comment", words))
        assertEquals(50, SocialPolicy.keywords((1..80).joinToString("\n") { "word$it" }).size)
        assertEquals(21, SocialPolicy.opacity(-1))
        assertEquals(100, SocialPolicy.opacity(101))
        assertEquals(54, SocialPolicy.alpha(21))
    }
    @Test fun perSeriesSurvivesRecreationWithoutChangingOtherSeriesOrGlobalOff() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val prefs = SocialPreferences(controller.get())
            prefs.enabled = true
            prefs.setSeriesEnabled("one", false)
            val reopened = SocialPreferences(controller.get())
            assertFalse(reopened.isEnabled("one"))
            assertTrue(reopened.isEnabled("two"))
            reopened.enabled = false
            reopened.setSeriesEnabled("one", true)
            assertFalse(reopened.isEnabled("one"))
            assertFalse(reopened.isEnabled("two"))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun portableSnapshotRestoresOnlyKnownSocialFieldsAndRejectsBeforeWrite() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val prefs = SocialPreferences(controller.get())
            prefs.enabled = true; prefs.opacity = 37; prefs.keywords = listOf("spoiler")
            prefs.setSeriesEnabled("series", false)
            val saved = prefs.exportState()
            prefs.setSeriesEnabled("series", true)
            prefs.enabled = false; prefs.opacity = 100; prefs.keywords = emptyList()
            prefs.restoreState(saved)
            assertTrue(prefs.enabled); assertEquals(37, prefs.opacity)
            assertEquals(listOf("spoiler"), prefs.keywords)
            assertFalse(prefs.seriesEnabled("series"))
            val before = prefs.exportState().toString()
            val invalid = org.json.JSONObject(before).put("font", 999)
            assertTrue(runCatching { prefs.restoreState(invalid) }.isFailure)
            assertEquals(before, prefs.exportState().toString())
            assertTrue(runCatching { SocialPreferences.validateState(org.json.JSONObject(before).put("device", "no")) }.isFailure)
        } finally { controller.pause().stop().destroy() }
    }
}
