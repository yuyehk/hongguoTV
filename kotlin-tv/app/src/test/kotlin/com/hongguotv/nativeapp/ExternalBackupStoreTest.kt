package com.hongguotv.nativeapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.hongguotv.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalBackupStoreTest {
    private class Documents(private val directory: File) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val columns = projection ?: arrayOf("document_id", "_display_name")
            return MatrixCursor(columns).apply {
                directory.listFiles().orEmpty().filter { it.isFile }.forEach { file -> addRow(columns.map { file.name }.toTypedArray()) }
            }
        }
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            check(method == "android:createDocument")
            val name = extras!!.getString("_display_name")!!
            check(name.matches(Regex("hongguotv-backup-(current|previous)\\.json")))
            File(directory, name).createNewFile()
            return Bundle().apply { putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(Uri.parse("content://test.hongguo.documents/tree/root"), name)) }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val name = DocumentsContract.getDocumentId(uri)
            check(name.matches(Regex("hongguotv-backup-(current|previous)\\.json")))
            return ParcelFileDescriptor.open(File(directory, name), ParcelFileDescriptor.parseMode(mode))
        }
        override fun getType(uri: Uri) = "application/json"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }
    private fun raw(id: String): String {
        val data = BackupData(listOf(Series(id, "备份测试")), emptyList(), emptySet(), emptyList(), BackupSettings(720, 1f, true, ContentType.COMIC, VideoFrameMode.FIT))
        return JSONObject().put("format", "hongguotv.local-tools").put("version", 1).put("savedAt", 1234)
            .put("library", LibraryBackup.encode(data)).put("tools", "{\"version\":1}").toString()
    }
    private fun setup(): Pair<ExternalBackupStore, File> {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "document-provider").apply { mkdirs() }
        val provider = Documents(directory)
        provider.attachInfo(context, ProviderInfo().apply { authority = "test.hongguo.documents" })
        ShadowContentResolver.registerProviderInternal("test.hongguo.documents", provider)
        val tree = Uri.parse("content://test.hongguo.documents/tree/root")
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return ExternalBackupStore(context).apply { remember(tree) } to directory
    }
    @Test fun authorizedDocumentProviderRoundTripAndCorruptCurrentRecovery() {
        val (store, directory) = setup()
        store.mirror(raw("1")).getOrThrow()
        assertEquals("1", store.loadLatest()!!.data.favorites.single().id)
        store.mirror(raw("2")).getOrThrow()
        assertEquals("2", store.loadLatest()!!.data.favorites.single().id)
        File(directory, "hongguotv-backup-current.json").writeText("{")
        assertEquals("1", store.loadLatest()!!.data.favorites.single().id)
    }
    @Test fun revokedGrantAndMalformedBackupKeepExistingFile() {
        val (store, directory) = setup()
        store.mirror(raw("1")).getOrThrow()
        val original = File(directory, "hongguotv-backup-current.json").readText()
        assertTrue(store.mirror("{invalid}").isFailure)
        assertEquals(original, File(directory, "hongguotv-backup-current.json").readText())
        RuntimeEnvironment.getApplication().contentResolver.releasePersistableUriPermission(Uri.parse("content://test.hongguo.documents/tree/root"), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertTrue(store.mirror(raw("2")).isFailure)
        assertEquals(original, File(directory, "hongguotv-backup-current.json").readText())
    }
}
