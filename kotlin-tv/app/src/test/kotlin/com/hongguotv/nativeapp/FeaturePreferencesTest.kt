// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeaturePreferencesTest {
    private lateinit var preferences: FeaturePreferences
    @Before fun fresh() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("feature-tools-v1", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = FeaturePreferences(app)
    }
    @Test fun groupsAreIndependentAndRemovingGroupPreservesSeries() {
        assertTrue(preferences.addGroup("家人"))
        preferences.assignGroup("100", "家人")
        assertEquals("家人", preferences.groupOf("100"))
        preferences.removeGroup("家人")
        assertNull(preferences.groupOf("100"))
        assertTrue(preferences.groups().isEmpty())
    }
    @Test fun statsRejectSleepGapsAndIncludeUnflushedPlayback() {
        preferences.recordPlayback(5000)
        preferences.recordPlayback(3_600_000)
        preferences.recordPlayback(-1)
        assertEquals(5000L, preferences.stats().playbackMs)
        preferences.flushStats()
        assertEquals(5000L, FeaturePreferences(RuntimeEnvironment.getApplication()).stats().playbackMs)
    }
    @Test fun malformedRestoreDoesNotPartiallyChangeGroups() {
        val assignments = JSONObject()
        repeat(1001) { assignments.put(it.toString(), "新组") }
        val payload = JSONObject().put("version", 1).put("groups", JSONArray(listOf("新组"))).put("assignments", assignments)
        try { preferences.restore(payload.toString()); fail("oversized assignments must be rejected") } catch (_: IllegalArgumentException) { }
        assertTrue(preferences.groups().isEmpty())
    }
    @Test fun exportDoesNotIncludeAccountOrSourcePreferences() {
        RuntimeEnvironment.getApplication().getSharedPreferences("third-sources-v1", Context.MODE_PRIVATE).edit()
            .putString("sources", "private-token").commit()
        assertFalse(preferences.export().contains("private-token"))
        assertFalse(preferences.autoBackup)
        assertEquals(FeatureKeyAction.DEFAULT, preferences.keyAction(82))
        assertTrue(preferences.nextSeason)
        preferences.nextSeason = false
        assertFalse(FeaturePreferences(RuntimeEnvironment.getApplication()).nextSeason)
    }
    @Test fun restoreAtCapacityPreservesExistingPerSeriesChoices() {
        repeat(500) { preferences.setSkip(it.toString(), 10, 0) }
        val incoming = JSONObject().put("version", 1).put("skips", JSONObject().put("new", JSONObject().put("intro", 30).put("outro", 0)))
        preferences.restore(incoming.toString())
        assertEquals(10, preferences.skip("0").introSeconds)
        assertEquals(0, preferences.skip("new").introSeconds)
    }
}
