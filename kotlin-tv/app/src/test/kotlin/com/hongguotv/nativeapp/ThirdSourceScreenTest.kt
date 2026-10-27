package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
class ThirdSourceScreenTest {
    @Test fun userCanReachPhoneImportAndOverrideMeasuredSourceOrderWithoutPlaying() {
        RuntimeEnvironment.getApplication().getSharedPreferences("third-sources-v1", 0).edit().clear().commit()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var played = false
        val screen = ThirdSourceScreen(activity) { _, _ -> played = true }
        val a = ThirdSource("a", "A", "https://a.example/api")
        val b = ThirdSource("b", "B", "https://b.example/api")
        screen.store.mergeSources(listOf(a, b)); screen.store.recordMeasurements(mapOf("a" to SourceMeasurement(100, true), "b" to SourceMeasurement(900, true)))
        fun latest(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
        fun click(index: Int) { latest().listView.performItemClick(null, index, index.toLong()) }
        try {
            screen.show(); click(0)
            assertTrue(latest().listView.adapter.getItem(2).toString().contains("手机扫码"))
            click(3); click(2)
            assertEquals("b", screen.store.preferredId()); assertEquals(listOf("b", "a"), screen.store.orderedSources().map { it.id })
            assertFalse(played)
            screen.close(); assertFalse(screen.showing)
        } finally { screen.destroy(); activity.finish() }
    }
}
