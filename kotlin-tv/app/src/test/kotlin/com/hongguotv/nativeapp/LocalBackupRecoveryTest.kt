// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.net.Uri
import com.hongguotv.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalBackupRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun unavailableExternalGrantCannotInvalidatePrivateBackup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("native-external-backup-v1", Context.MODE_PRIVATE).edit().clear().commit()
        val external = ExternalBackupStore(app)
        external.remember(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ABackup"))
        val storage = LocalBackupStorage(temp.newFolder(), external)
        val data = BackupData(listOf(Series("1", "saved")), emptyList(), emptySet(), emptyList(), BackupSettings())
        storage.save(LibraryBackup.encode(data), "{\"version\":1}")
        assertEquals("1", storage.load()!!.data.favorites.single().id)
        assertTrue(storage.externalStatus().contains("外部复制失败"))
    }
}
