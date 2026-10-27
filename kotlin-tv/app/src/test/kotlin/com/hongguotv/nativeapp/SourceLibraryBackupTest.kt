package com.hongguotv.nativeapp

import android.content.Context
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
class SourceLibraryBackupTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun reset() { listOf("third-sources-v1", "source-portable-progress-v1").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() } }
    private fun fixture(id: String = "a", token: String = "private"): SourcePlaylist {
        val source = ThirdSource(id, "A", "https://example.org/api?token=$token")
        val detail = ThirdDetail(ThirdVideo("v", "Fixture"), "", listOf("line" to listOf(ThirdEpisode("one", "https://example.org/video?token=renewed"))))
        return SourcePlaylist(source, detail, 0, 0)
    }
    @Test fun completeBackupRestoresSourceFavoritesHistoryStableProgressAndSeriesSettings() {
        val list = fixture(); val store = ThirdStore(context); val progress = SourceProgressStore(context)
        store.mergeSources(listOf(list.source)); store.toggleFavorite(list.source, list.detail.video); store.recordSelection(list.source, list.detail, 0, 0)
        progress.save(list, 0, 4321); progress.setSkips(list, 10000, 30000)
        val backup = SourceLibraryBackup(context).exportState(); SourceLibraryBackup.validate(backup)
        reset(); SourceLibraryBackup(context).restoreState(backup)
        assertEquals("private", ThirdStore(context).sources().single().api.substringAfter("token="))
        assertEquals(1, ThirdStore(context).favorites().size); assertEquals(1, ThirdStore(context).history().size)
        assertEquals(4321, SourceProgressStore(context).position(list.progressKey(0)))
        assertEquals(10000L to 30000L, SourceProgressStore(context).skips(list.seriesKey))
        assertFalse(backup.contains("device_id")); assertFalse(backup.contains("account")); assertFalse(backup.contains("renewed"))
    }
    @Test fun invalidLastPartitionCannotPartiallyWriteSourcesOrFavorites() {
        val list = fixture(); ThirdStore(context).mergeSources(listOf(list.source))
        val raw = JSONObject(SourceLibraryBackup(context).exportState()).put("series", "bad").toString()
        reset()
        assertTrue(runCatching { SourceLibraryBackup(context).restoreState(raw) }.isFailure)
        assertTrue(ThirdStore(context).sources().isEmpty())
    }
    @Test fun olderImportKeepsNewerProgressAndRemapsEquivalentSourceIdentifiers() {
        val list = fixture(); ThirdStore(context).mergeSources(listOf(list.source)); SourceProgressStore(context).save(list, 0, 1000)
        val root = JSONObject(SourceLibraryBackup(context).exportState()); root.getJSONArray("progress").getJSONObject(0).put("at", 1)
        val raw = root.toString(); reset()
        val existing = fixture("existing"); ThirdStore(context).mergeSources(listOf(existing.source)); SourceProgressStore(context).save(existing, 0, 9000)
        SourceLibraryBackup(context).restoreState(raw)
        assertEquals("existing", ThirdStore(context).sources().single().id)
        assertEquals(9000, SourceProgressStore(context).position(existing.progressKey(0)))
        SourceLibraryBackup.validate(SourceLibraryBackup(context).exportState())
    }
    @Test fun collidingLocalIdWithDifferentApiIsRejectedBeforeRecordMutation() {
        val incoming=fixture();ThirdStore(context).mergeSources(listOf(incoming.source));SourceProgressStore(context).save(incoming,0,1234)
        val raw=SourceLibraryBackup(context).exportState();reset()
        val local=fixture("a","different-local");ThirdStore(context).mergeSources(listOf(local.source))
        assertTrue(runCatching {SourceLibraryBackup(context).restoreState(raw)}.isFailure)
        assertEquals(local.source.api,ThirdStore(context).sources().single().api)
        assertEquals(0,SourceProgressStore(context).progress().length())
    }
    @Test fun deletingSourceRemovesItsPortableProgress() {
        val list = fixture(); ThirdStore(context).mergeSources(listOf(list.source)); SourceProgressStore(context).save(list, 0, 4000)
        ThirdStore(context).removeSource(list.source.id)
        SourceLibraryBackup.validate(SourceLibraryBackup(context).exportState())
        assertEquals(0, SourceProgressStore(context).progress().length())
    }
    @Test fun missingStableProgressCanMigrateLegacyUrlWhileCompletedZeroRemainsAuthoritative() {
        val list = fixture(); val progress = SourceProgressStore(context)
        assertNull(progress.positionOrNull(list.progressKey(0)))
        progress.save(list, 0, 0)
        assertEquals(0L, progress.positionOrNull(list.progressKey(0)))
    }
    @Test fun fullExistingSourceListCannotCreateOrphanProgressFromAnExtraImportedSource() {
        val list = fixture(); ThirdStore(context).mergeSources(listOf(list.source)); SourceProgressStore(context).save(list, 0, 4000)
        val raw = SourceLibraryBackup(context).exportState(); reset()
        ThirdStore(context).mergeSources((1..30).map { ThirdSource("local$it", "Local$it", "https://local$it.example/api") })
        assertTrue(runCatching { SourceLibraryBackup(context).restoreState(raw) }.isFailure)
        assertEquals(30, ThirdStore(context).sources().size)
        assertEquals(0, SourceProgressStore(context).progress().length())
        SourceLibraryBackup.validate(SourceLibraryBackup(context).exportState())
    }
}
