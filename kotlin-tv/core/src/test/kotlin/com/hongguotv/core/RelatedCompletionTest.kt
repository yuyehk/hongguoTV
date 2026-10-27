package com.hongguotv.core

import java.io.IOException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class RelatedCompletionTest {
    private val current = Series("100", "《归来》 第一季")
    private fun http(answer: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
            .message("OK").body(answer(chain.request()).toResponseBody()).build()
    }.build()

    @Test fun searchMatchesAreManualOnlyDeduplicatedAndSeasonSorted() {
        val official = RelatedWork(Series("200", "归来 第二季"), "第2季", false)
        val result = SeasonFamilies.merge(current, listOf(official), listOf(
            Series("300", "归来 第三季"), Series("201", "归来 第二季"),
            Series("100", current.title), Series("200", "归来 第二季"),
            Series("900", "归来之后 第二季"), Series("400", "归来")))
        assertEquals(listOf("200", "201", "300", "400"), result.map { it.series.id })
        assertTrue(result.first().confirmed)
        assertTrue(result.drop(1).none { it.confirmed })
        assertNull(SeasonContinuation.next(current, result.filterNot { it.confirmed }))
        assertEquals(official.series, SeasonContinuation.next(current, result))
    }

    @Test fun pageResultsStayPartialUntilExplicitLoadMoreAndAreCached() {
        var searches = 0
        var related = 0
        val repository = ContentRepository(http { request ->
            if (request.url.encodedPath.contains("plan/")) {
                related++
                """{"code":0,"data":[]}"""
            } else {
                searches++
                val page = request.url.queryParameter("page")!!.toInt()
                """{"pageNum":$page,"hasMore":${page == 1},"totalPages":2,"searchList":[{"video_data":{"series_id":"${page + 1}00","series_title":"归来 第${page + 1}季"}}]}"""
            }
        })
        val first = repository.relatedComplete(current)
        assertEquals(2, first.nextPage)
        assertFalse(first.searchComplete)
        assertEquals(first, repository.relatedComplete(current))
        val second = repository.relatedComplete(current, 2)
        assertEquals(listOf("200", "300"), second.items.map { it.series.id })
        assertTrue(second.searchComplete)
        assertNull(second.nextPage)
        assertEquals(2, searches)
        assertEquals(1, related)
    }

    @Test fun failedPageRetainsRowsAndRetryPageAndMissingOfficialDoesNotClaimAllSeasons() {
        var failSecond = true
        val repository = ContentRepository(http { request ->
            if (request.url.encodedPath.contains("plan/")) throw IOException("unavailable")
            val page = request.url.queryParameter("page")!!.toInt()
            if (page == 2 && failSecond) throw IOException("offline")
            """{"pageNum":$page,"hasMore":${page == 1},"totalPages":2,"searchList":[{"video_data":{"series_id":"200","series_title":"归来 第二季"}}]}"""
        })
        val first = repository.relatedComplete(current)
        assertTrue(first.message.contains("官方"))
        val failure = repository.relatedComplete(current, 2)
        assertEquals(first.items, failure.items)
        assertFalse(failure.searchComplete)
        assertEquals(2, failure.nextPage)
        failSecond = false
        val retried = repository.relatedComplete(current, 2)
        assertTrue(retried.searchComplete)
        assertTrue(retried.message.contains("官方"))
    }

    @Test fun rejectsSkippedOrExpiredPageAndDoesNotResetUserSearchSession() {
        var now = 1_000L
        val queries = mutableListOf<String>()
        val repository = ContentRepository(http { request ->
            if (request.url.encodedPath.contains("plan/")) """{"code":0,"data":[]}"""
            else {
                queries += request.url.queryParameter("query").orEmpty()
                val page = request.url.queryParameter("page")!!.toInt()
                """{"pageNum":$page,"hasMore":true,"totalPages":3,"searchList":[]}"""
            }
        }, { now })
        repository.searchAll("归来", 1)
        assertThrows(SearchSessionExpiredException::class.java) { repository.relatedComplete(current, 2) }
        repository.relatedComplete(current)
        repository.searchAll("归来", 2)
        assertEquals(listOf("归来", "归来", "归来"), queries)
        now += 600_001
        assertThrows(SearchSessionExpiredException::class.java) { repository.relatedComplete(current, 2) }
    }

    @Test fun seasonLookupDoesNotConsumeCatalogAppSearchCursor() {
        val appRequests = mutableListOf<Request>()
        val repository = ContentRepository(http { request ->
            when {
                request.url.encodedPath.contains("plan/") -> """{"code":0,"data":[]}"""
                request.url.encodedPath.contains("/search/tab/") -> {
                    appRequests += request
                    """{"code":0,"search_tabs":[{"tab_type":19,"data":[{"video_data":[{"series_id":"999","title":"归来","episode_cnt":10}]}],"has_more":true,"search_id":"catalog-session","passback":"catalog-token","next_offset":25}]}"""
                }
                else -> """{"pageNum":1,"hasMore":false,"searchList":[]}"""
            }
        })
        repository.search("归来", type = ContentType.COMIC)
        repository.relatedComplete(current)
        repository.search("归来", 2, ContentType.COMIC)
        assertEquals(2, appRequests.size)
        assertEquals("catalog-session", appRequests[1].url.queryParameter("search_id"))
        assertEquals("catalog-token", appRequests[1].url.queryParameter("passback"))
        assertEquals("25", appRequests[1].url.queryParameter("offset"))
    }

    @Test fun emptyPageStillAllowsMoreAndHundredPageBoundNeverClaimsComplete() {
        val repository = ContentRepository(http { request ->
            if (request.url.encodedPath.contains("plan/")) """{"code":0,"data":[]}"""
            else """{"pageNum":${request.url.queryParameter("page")},"hasMore":true,"searchList":[]}"""
        })
        var result = repository.relatedComplete(current)
        assertEquals(2, result.nextPage)
        for (page in 2..100) result = repository.relatedComplete(current, page)
        assertNull(result.nextPage)
        assertFalse(result.searchComplete)
        assertTrue(result.message.contains("上限"))
    }

    @Test fun cancellationNeverBecomesAnEmptyCompletedList() {
        val scope = RequestScope()
        var calls = 0
        val repository = ContentRepository(http { request ->
            calls++
            scope.cancel()
            throw IOException("Canceled")
        })
        assertThrows(IOException::class.java) { scope.run { repository.relatedComplete(current) } }
        assertEquals(1, calls)
    }
}
