// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
class LocalBackupStorageTest {
    @get:Rule val temp = TemporaryFolder()
    private fun payload(id: String) = LibraryBackup.encode(BackupData(listOf(Series(id, "Test $id")), emptyList(), emptySet(), emptyList(), BackupSettings()))
    @Test fun damagedCurrentRecoversPriorValidatedCopy() {
        val directory = temp.newFolder()
        val storage = LocalBackupStorage(directory)
        storage.save(payload("1"), "{\"version\":1}", 1000)
        storage.save(payload("2"), "{\"version\":1}", 2000)
        java.io.File(directory, "current.json").writeText("broken")
        assertEquals("1", storage.load()!!.data.favorites.single().id)
    }
    @Test fun invalidIncomingBackupCannotReplaceSuccessfulCopy() {
        val storage = LocalBackupStorage(temp.newFolder())
        storage.save(payload("1"), "{\"version\":1}")
        try { storage.save("broken", "{}"); fail("invalid backup must be rejected") } catch (_: Exception) { }
        assertEquals("1", storage.load()!!.data.favorites.single().id)
    }
}
