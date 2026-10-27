package com.hongguotv.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CloudCoverageTest {
    private fun client(answer: (Request) -> String) = CloudAccountClient(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
            .message("OK").body(answer(chain.request()).toResponseBody()).build()
    }.build())

    @Test fun historyReportsRecentPartialWithoutInventingCursorEvenWhenServerOffersUnknownFields() {
        var calls = 0
        val page = client { request ->
            calls++
            assertEquals("50", request.url.queryParameter("limit"))
            assertNull(request.url.queryParameter("offset"))
            assertNull(request.url.queryParameter("cursor"))
            """{"code":0,"video_data":[],"has_more":true,"next_cursor":"unverified"}"""
        }.historyPage(AccountCredentials("fixture"))
        assertEquals(1, calls)
        assertFalse(page.coverage.complete)
        assertFalse(page.coverage.paginationSupported)
        assertEquals(true, page.coverage.serverHasMore)
        assertEquals(50, page.coverage.requestLimit)
        assertTrue(page.coverage.message.contains("最近"))
    }

    @Test fun emptyFavoritesDoNotProveWholeAccountAndUnknownPaginationIsNotCoerced() {
        val page = client {
            """{"code":0,"data":{"video_shelf_info":[],"has_more":"false","next_offset":20}}"""
        }.favoritesPage(AccountCredentials("fixture"))
        assertNull(page.coverage.serverHasMore)
        assertFalse(page.coverage.complete)
        assertTrue(page.coverage.message.contains("未验证"))
    }

    @Test fun reservationUsesOnlyVerifiedFirstPageAndLabelsTwentyItemLimitPerCategory() {
        var calls = 0
        val page = client { request ->
            calls++
            assertEquals("0", request.url.queryParameter("offset"))
            assertEquals("0", request.url.queryParameter("subscribe_offset"))
            assertEquals("20", request.url.queryParameter("limit"))
            """{"code":0,"data":{"subscribe_items":[],"has_more":false}}"""
        }.reservationsPage(AccountCredentials("fixture"), false)
        assertEquals(1, calls)
        assertEquals(false, page.coverage.serverHasMore)
        assertEquals(20, page.coverage.requestLimit)
        assertFalse(page.coverage.complete)
    }
}
