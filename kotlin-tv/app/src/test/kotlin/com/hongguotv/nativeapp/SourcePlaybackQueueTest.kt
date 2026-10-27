package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class SourcePlaybackQueueTest {
    @Test fun queueIsSingleUseMatchesSelectedItemAndSurvivesBoundedStateRoundTrip() {
        val source = ThirdSource("a", "A", "https://example.org/api")
        val detail = ThirdDetail(ThirdVideo("id", "Title"), "", listOf("one" to listOf(ThirdEpisode("1", "https://example.org/1"), ThirdEpisode("2", "https://example.org/2"))))
        SourcePlaybackQueue.offer(source, detail, 0, 1)
        val queue = SourcePlaybackQueue.claim("https://example.org/2", "Title · 2")!!
        assertEquals(1, queue.index); assertEquals(2, queue.episodes.size)
        assertNull(SourcePlaybackQueue.claim("https://example.org/2", "Title · 2"))
        assertEquals(queue, SourcePlaybackQueue.decode(SourcePlaybackQueue.encode(queue)))
        SourcePlaybackQueue.offer(source, detail, 0, 0)
        assertNull(SourcePlaybackQueue.claim("https://evil.org/1", "Title · 1"))
        assertNull(SourcePlaybackQueue.claim("https://example.org/1", "Title · 1"))
    }
    @Test fun largePlaylistKeepsSelectedEpisodeInsideASafeSavedStateBound() {
        val source = ThirdSource("a", "A", "https://example.org/api")
        val episodes = (0 until 2000).map { ThirdEpisode("ep$it", "https://example.org/$it?token=" + "x".repeat(3000)) }
        SourcePlaybackQueue.offer(source, ThirdDetail(ThirdVideo("id", "Title"), "", listOf("line" to episodes)), 0, 1500)
        val queue = SourcePlaybackQueue.claim(episodes[1500].url, "Title · ep1500")!!
        assertEquals("ep1500", queue.episodes[queue.index].label)
        assertTrue(SourcePlaybackQueue.encode(queue).toByteArray().size <= 196608)
        assertEquals(1500, queue.index + queue.episodeOffset)
    }
}
