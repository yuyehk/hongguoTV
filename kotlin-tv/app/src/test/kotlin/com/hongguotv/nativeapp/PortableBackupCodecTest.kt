package com.hongguotv.nativeapp

import com.hongguotv.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortableBackupCodecTest {
    private fun envelope(): JSONObject {
        val data = BackupData(emptyList(), emptyList(), emptySet(), emptyList(), BackupSettings(720, 1f, true, ContentType.COMIC, VideoFrameMode.FIT))
        return JSONObject().put("format", "hongguotv.local-tools").put("version", 1).put("savedAt", 1234)
            .put("library", LibraryBackup.encode(data)).put("tools", JSONObject().put("version", 1).put("groups", org.json.JSONArray()).put("accountCookie", "never-export").toString())
    }
    @Test fun portableEnvelopeDropsUnknownCredentialsAndKeepsValidatedRecords() {
        val raw = envelope().put("login", "secret").toString()
        val safe = PortableBackupCodec.sanitize(raw)
        assertFalse(safe.contains("never-export"))
        assertFalse(safe.contains("secret"))
        assertEquals(1234, PortableBackupCodec.decode(safe).savedAt)
        assertTrue(PortableBackupCodec.decode(safe).data.favorites.isEmpty())
    }
    @Test fun malformedAndOversizedPayloadsNeverBecomeBackups() {
        for (raw in listOf("{", envelope().put("version", 99).toString(), envelope().put("library", "{}").toString(), "x".repeat(PortableBackupCodec.MAX_BYTES + 1))) {
            assertTrue(runCatching { PortableBackupCodec.sanitize(raw) }.isFailure)
        }
    }
    @Test fun repeatedValidationKeepsIdenticalBytesForReadBackVerification() {
        val first = PortableBackupCodec.sanitize(envelope().toString())
        Thread.sleep(5)
        assertEquals(first, PortableBackupCodec.sanitize(first))
    }
}
