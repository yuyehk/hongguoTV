// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThirdStoreTest {
    private lateinit var store: ThirdStore
    @Before fun fresh() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("third-sources-v1", Context.MODE_PRIVATE).edit().clear().commit()
        store = ThirdStore(app)
    }
    @Test fun freshSourceStoreIsEmptyAndDoesNotTouchOfficialLibrary() {
        assertTrue(store.sources().isEmpty())
        assertTrue(store.favorites().isEmpty())
        assertTrue(store.history().isEmpty())
        assertNull(store.currentId())
    }
    @Test fun sourceSelectionAndFavoritesRemainSeparateBySource() {
        val a = ThirdSource("a", "A", "https://a.example/api?token=private")
        val b = ThirdSource("b", "B", "https://b.example/api")
        store.mergeSources(listOf(a, b, a))
        val video = ThirdVideo("1", "same ID")
        store.toggleFavorite(a, video); store.toggleFavorite(b, video)
        store.recordSelection(a, ThirdDetail(video, "", listOf("line" to listOf(ThirdEpisode("one", "https://a.example/play.m3u8")))), 0, 0)
        assertEquals(2, store.favorites().size)
        assertEquals("one", store.history().single().episodeLabel)
        store.removeSource("a")
        assertEquals(listOf("b"), store.favorites().map { it.sourceId })
        assertTrue(store.history().isEmpty())
        assertFalse(FeaturePreferences(RuntimeEnvironment.getApplication()).export().contains("private"))
    }
}
