// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class ThirdCmsTest {
    @Test fun endpointValidationRejectsExecutableAndCredentialUrls() {
        assertFalse(ThirdCms.validUrl("javascript:alert(1)"))
        assertFalse(ThirdCms.validUrl("https://name:secret@example.org/api.php"))
        assertFalse(ThirdCms.validUrl("https://example.org/api.php#fragment"))
        assertTrue(ThirdCms.validUrl("http://192.168.1.2/api.php?token=abc"))
    }
    @Test fun importKeepsOnlyExplicitCmsAndDeduplicatesWithoutSeedSources() {
        val result = ThirdCms.parseSources("""{"sites":[
            {"name":"one","type":1,"api":"https://example.org/api.php"},
            {"name":"copy","type":1,"api":"https://example.org/api.php"},
            {"name":"jar","type":3,"api":"https://example.org/spider.jar"},
            {"name":"script","type":1,"api":"javascript:bad"}]}""")
        assertEquals(listOf("one"), result.map { it.name })
        assertEquals(emptyList<ThirdSource>(), ThirdCms.parseSources("{\"sites\":[]}"))
    }
    @Test fun executableFilesCannotMasqueradeAsTypeOneJsonInterfaces() {
        val imported = ThirdCms.parseSources("""{"sites":[{"name":"plugin","type":1,"api":"https://example.org/plugin.jar"},{"name":"script","type":1,"api":"https://example.org/plugin.js?token=x"}]}""")
        assertTrue(imported.isEmpty())
    }
    @Test fun playbackGroupsKeepLinesAndFilterUnsupportedUrls() {
        val groups = ThirdCms.parsePlayGroups("main\$\$\$backup", "第1集\$https://example.org/1.m3u8#bad\$javascript:bad\$\$\$https://example.org/2.mp4")
        assertEquals(2, groups.size)
        assertEquals("第1集", groups[0].second.single().label)
        assertEquals("backup", groups[1].first)
        assertEquals("https://example.org/2.mp4", groups[1].second.single().url)
    }
    @Test fun queryBuilderPreservesUserQueryAndEncodesSearch() {
        val url = ThirdCms.apiUrl("https://example.org/api.php?token=abc", listOf("ac" to "detail", "wd" to "甜 剧&新"))
        val parsed = url.toHttpUrl()
        assertEquals("abc", parsed.queryParameter("token"))
        assertEquals("甜 剧&新", parsed.queryParameter("wd"))
    }
    @Test fun listParsingRejectsFalseSuccessAndStopsEmptyPagination() {
        assertEquals(false, ThirdCms.parseList("{\"list\":[],\"page\":1,\"pagecount\":20}").hasMore)
        try { ThirdCms.parseList("{\"code\":0,\"msg\":\"failed\"}"); fail("missing list must fail") } catch (_: IllegalArgumentException) { }
    }
    @Test fun aggregateGroupsKeepAlternateSourcesAndRemoveDuplicateHits() {
        val first = ThirdSource("a", "a", "https://a.example/api")
        val second = ThirdSource("b", "b", "https://b.example/api")
        val a = ThirdSearchHit(first, ThirdVideo("1", "《同名剧》"))
        val b = ThirdSearchHit(second, ThirdVideo("2", "同名剧"))
        val result = ThirdCms.groupSearchHits(listOf(a, a, b))
        assertEquals(1, result.size)
        assertEquals(listOf("a", "b"), result.single().hits.map { it.source.id })
    }
    @Test fun aggregatePaginationRetainsOrderDeduplicatesAndKeepsPartialFailures() {
        val a = ThirdSource("a", "a", "https://a.example/api")
        val first = ThirdSearchHit(a, ThirdVideo("1", "《same》"))
        val second = ThirdSearchHit(a, ThirdVideo("2", "New"))
        val old = ThirdAggregateResult(ThirdCms.groupSearchHits(listOf(first)), 1, 2, 1, listOf("a"), listOf("b"))
        val next = ThirdAggregateResult(ThirdCms.groupSearchHits(listOf(first, second)), 1, 1, 2)
        val merged = ThirdCms.mergeAggregates(old, next)
        assertEquals(listOf("1", "2"), merged.groups.flatMap { it.hits }.map { it.video.id })
        assertEquals(listOf("b"), merged.failedSourceIds); assertEquals(2, merged.totalSources)
        assertEquals(2, merged.page); assertTrue(merged.nextSourceIds.isEmpty())
    }
}
