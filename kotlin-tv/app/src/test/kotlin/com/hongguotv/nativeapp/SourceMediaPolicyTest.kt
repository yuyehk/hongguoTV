package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class SourceMediaPolicyTest {
    @Test fun acceptsHttpMediaAndLanSourcesButRejectsExecutableSchemes() {
        assertTrue(SourceMediaPolicy.valid("https://cdn.example.test/play.m3u8?token=abc"))
        assertTrue(SourceMediaPolicy.valid("http://192.168.1.2:8080/movie.mp4"))
        for (value in listOf("file:///sdcard/movie.mp4", "javascript:alert(1)", "intent://play", "https:///bad", "https://user:pass@example.test/a"))
            assertFalse(value, SourceMediaPolicy.valid(value))
    }
    @Test fun boundedIdentityDoesNotStoreTheUrlOrCollapseDifferentMedia() {
        val first = SourceMediaPolicy.key("https://example.test/a?secret=token")
        assertEquals(64, first.length)
        assertFalse(first.contains("secret"))
        assertNotEquals(first, SourceMediaPolicy.key("https://example.test/b"))
        assertFalse(SourceMediaPolicy.valid("https://example.test/" + "a".repeat(9000)))
    }
    @Test fun repeatedSavesAfterCompletionKeepResumeAtTheBeginning() {
        assertEquals(0L, SourceMediaPolicy.resumePosition(90000, true))
        assertEquals(0L, SourceMediaPolicy.resumePosition(90000, true))
        assertEquals(45000L, SourceMediaPolicy.resumePosition(45000, false))
        assertEquals(0L, SourceMediaPolicy.resumePosition(-1, false))
    }
}
