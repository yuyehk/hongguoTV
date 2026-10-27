package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.Series
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FavoriteSeasonStoreTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val prefs get()=app.getSharedPreferences("native-favorite-seasons-v1",Context.MODE_PRIVATE)
    private val first=Series("100","归来 第一季")
    private val second=Series("200","归来 第二季")
    @Before fun fresh() { prefs.edit().clear().commit() }

    @Test fun firstObservationIsAvailableSeasonAndSurvivesRecreation() {
        val store=FavoriteSeasonStore(app)
        store.observe(first,second)
        val notice=FavoriteSeasonStore(app).pending().single()
        assertEquals(first,notice.current)
        assertEquals(second,notice.next)
        assertEquals("可看下一季",notice.label)
    }

    @Test fun acknowledgingSameCandidateDoesNotRenotifyButDifferentCandidateDoes() {
        val store=FavoriteSeasonStore(app)
        store.observe(first,second)
        store.acknowledge(first.id)
        FavoriteSeasonStore(app).observe(first,second)
        assertTrue(FavoriteSeasonStore(app).pending().isEmpty())
        val third=Series("300","归来 第三季")
        store.observe(first,third)
        assertEquals(third,FavoriteSeasonStore(app).pending().single().next)
    }

    @Test fun pruningUnfavoritedSeriesAlsoRemovesAcknowledgedCandidateState() {
        val store=FavoriteSeasonStore(app)
        store.observe(first,second)
        store.acknowledge(first.id)
        store.prune(emptySet())
        store.observe(first,second)
        assertEquals(1,store.pending().size)
        store.prune(setOf("unrelated"))
        assertTrue(FavoriteSeasonStore(app).pending().isEmpty())
    }

    @Test fun boundedStoreRetainsMostRecentlyObservedFiveHundredSeries() {
        val store=FavoriteSeasonStore(app)
        repeat(501) { index ->
            store.observe(Series((1000+index).toString(),"作品$index 第一季"),Series((10000+index).toString(),"作品$index 第二季"))
        }
        val pending=FavoriteSeasonStore(app).pending()
        assertEquals(500,pending.size)
        assertFalse(pending.any { it.current.id=="1000" })
        assertTrue(pending.any { it.current.id=="1500" })
    }

    @Test fun invalidPreferenceTypeAndMalformedJsonAreTreatedAsEmpty() {
        prefs.edit().putInt("entries",42).commit()
        assertTrue(FavoriteSeasonStore(app).pending().isEmpty())
        prefs.edit().putString("entries","invalid-json").commit()
        val store=FavoriteSeasonStore(app)
        assertTrue(store.pending().isEmpty())
        store.observe(first,second)
        assertEquals(1,FavoriteSeasonStore(app).pending().size)
    }

    @Test fun invalidSeriesAndSelfReferencesCannotCreateNotices() {
        val store=FavoriteSeasonStore(app)
        store.observe(first,first)
        store.observe(first,Series("invalid","坏数据"))
        store.observe(Series("100",""),second)
        assertTrue(store.pending().isEmpty())
    }

    @Test fun noticesUseSeparatePreferencesAndDoNotModifyLibraryRecords() {
        val library=app.getSharedPreferences("native-library-v1",Context.MODE_PRIVATE)
        library.edit().putString("favorites","sentinel-library").commit()
        FavoriteSeasonStore(app).observe(first,second)
        assertEquals("sentinel-library",library.getString("favorites",null))
    }
}
