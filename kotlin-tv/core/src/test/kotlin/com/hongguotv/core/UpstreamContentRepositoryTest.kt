package com.hongguotv.core

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpstreamContentRepositoryTest {
    @Test
    fun inlinePlaybackModelUsesItsOwnMetadataAndKeyWithoutFallbackRequest() {
        val requests = mutableListOf<Request>()
        // Synthetic obfuscation fixture for 000102030405060708090a0b0c0d0e0f.
        val spade = "08AThlTBE4hbwRyKVMYciFXFEopa2hKjW9oToVrdE6NZ3yRftNQ5Xkc="
        val meta =
            JSONObject()
                .put("definition", "720p")
                .put("codec_type", "h264")
                .put("vwidth", 720)
                .put("vheight", 1280)
        val row =
            JSONObject()
                .put("main_url", "https://example.com/inline")
                .put("video_meta", meta)
                .put("encrypt_info", JSONObject().put("encrypt", true).put("spade_a", spade))
        val repo = repository { request ->
            requests += request
            check(request.url.host != "example.com")
            JSONObject()
                .put(
                    "data",
                    JSONObject()
                        .put(
                            "123",
                            JSONObject()
                                .put(
                                    "video_model",
                                    JSONObject()
                                        .put("video_list", JSONArray().put(row))
                                        .put("fallback_api", "https://example.com/fallback"),
                                ),
                        ),
                )
                .toString()
        }
        val tier = repo.streamLadder("123").renditions.single()
        assertEquals(1, requests.size)
        assertEquals("https://example.com/inline", tier.url)
        assertEquals(720, tier.quality)
        assertEquals(1280, tier.height)
        assertArrayEquals(ByteArray(16) { it.toByte() }, tier.key)
    }

    @Test
    fun unusableInlineEncryptedOrUnsupportedEntriesKeepLegacyFallback() {
        for (encryption in
            listOf(
                JSONObject().put("encrypt", true),
                JSONObject().put("encrypt", true).put("spade_a", "broken"),
            )) {
            var calls = 0
            val repo = repository { request ->
                calls++
                if (request.url.host == "example.com")
                    """{"video_info":{"data":{"video_list":{"v":{"quality_desc":"480p","codec_type":"h264","main_url":"https://example.com/valid"}}}}}"""
                else
                    JSONObject()
                        .put(
                            "data",
                            JSONObject()
                                .put(
                                    "123",
                                    JSONObject()
                                        .put(
                                            "video_model",
                                            JSONObject()
                                                .put(
                                                    "video_list",
                                                    JSONArray()
                                                        .put(
                                                            JSONObject()
                                                                .put(
                                                                    "main_url",
                                                                    "https://example.com/encrypted",
                                                                )
                                                                .put(
                                                                    "video_meta",
                                                                    JSONObject()
                                                                        .put("definition", "720p")
                                                                        .put("codec_type", "h264")
                                                                        .put("vwidth", 720)
                                                                        .put("vheight", 1280),
                                                                )
                                                                .put("encrypt_info", encryption)
                                                        ),
                                                )
                                                .put("fallback_api", "https://example.com/fallback"),
                                        ),
                                ),
                        )
                        .toString()
            }
            assertEquals(
                "https://example.com/valid",
                repo.streamLadder("123").renditions.single().url,
            )
            assertEquals(2, calls)
        }
    }

    @Test
    fun allSearchUsesPublicJsonEndpointAndKeepsBothContentTypes() {
        var calls = 0
        val repo = repository { request ->
            calls++
            assertEquals("hongguoduanju.com", request.url.host)
            assertEquals("/api/search/result", request.url.encodedPath)
            assertEquals("大佬", request.url.queryParameter("query"))
            assertEquals("2", request.url.queryParameter("page"))
            assertNull(request.url.queryParameter("tab_type"))
            assertNull(request.header("x-gorgon"))
            """{"pageNum":2,"hasMore":true,"searchList":[{"video_data":{"series_id":"123","series_title":"真人短剧","episode_cnt":80}},{"video_data":{"series_id":"456","series_title":"动画漫剧","episode_cnt":100}}]}"""
        }
        val page = repo.searchAll("大佬", 2)
        assertEquals(listOf("123", "456"), page.items.map { it.id })
        assertEquals(1, calls)
        assertTrue(page.hasMore)
    }

    @Test
    fun jsonSearchHandlesLastPageEmptyResultsAndChangedResponses() {
        assertFalse(
            repository {
                    """{"pageNum":2,"totalPages":2,"searchList":[{"video_data":{"series_id":"1","series_title":"最后一页"}}]}"""
                }
                .searchAll("测试", 2)
                .hasMore
        )
        val empty =
            repository { """{"pageNum":1,"totalPages":0,"hasMore":false,"searchList":[]}""" }
                .searchAll("无结果")
        assertTrue(empty.items.isEmpty())
        assertFalse(empty.hasMore)
        assertThrows(IOException::class.java) {
            repository { "<html>client rendered page</html>" }.searchAll("测试")
        }
        assertThrows(IOException::class.java) {
            repository { """{"pageNum":1,"searchList":[]}""" }.searchAll("测试", 2)
        }
        assertThrows(IOException::class.java) {
            repository { """{"code":"search_verification_required"}""" }.searchAll("测试")
        }
    }

    @Test
    fun allSearchDoesNotTurnNetworkFailureIntoEmptyResults() {
        val repo = repository { throw IOException("offline") }
        assertThrows(IOException::class.java) { repo.searchAll("大佬") }
    }

    @Test
    fun playbackUsesSignedPersistedIdentifiersAndKeepsThemAcrossEpisodes() {
        val requests = mutableListOf<Request>()
        val repo = repository { request ->
            requests += request
            """{"Code":110001}"""
        }
        repeat(2) {
            val error = assertThrows(IOException::class.java) { repo.stream("123") }
            assertTrue(error.message.orEmpty().contains("110001"))
        }
        for (request in requests) {
            assertEquals("POST", request.method)
            for (header in listOf("x-gorgon", "x-medusa", "x-argus", "x-ladon")) assertNotNull(request.header(header))
            assertEquals("/novel/player/multi_video_model/preload/v1/", request.url.encodedPath)
            for (key in listOf("iid", "device_id")) {
                val value=request.url.queryParameter(key)!!
                assertEquals(VendorConstants.device[key], value)
                assertEquals(requests.first().url.queryParameter(key),value)
            }
            val body = okio.Buffer()
            request.body!!.writeTo(body)
            assertEquals(
                "123",
                JSONObject(body.readUtf8())
                    .getJSONObject("mixed_video_id_map")
                    .getJSONArray("1004")
                    .getString(0),
            )
        }
    }

    @Test
    fun emptyPlaybackResponseReportsActionableError() {
        val repo = repository { "   " }
        val error = assertThrows(IOException::class.java) { repo.stream("123") }
        assertTrue(error.message.orEmpty().contains("空响应"))
    }

    private fun streamRepository(vararg variants: Triple<Int, String, String>) =
        repository { request ->
            if (request.url.host == "example.com") {
                val rows = JSONObject()
                variants.forEachIndexed { index, (height, codec, url) ->
                    rows.put(
                        "video_$index",
                        JSONObject()
                            .put("vheight", height)
                            .put("codec_type", codec)
                            .put("main_url", url),
                    )
                }
                JSONObject()
                    .put(
                        "video_info",
                        JSONObject().put("data", JSONObject().put("video_list", rows)),
                    )
                    .toString()
            } else
                """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
        }

    @Test
    fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo =
            streamRepository(
                Triple(720, "bytevc2", "https://example.com/unsupported"),
                Triple(1080, "bytevc1", "https://example.com/hevc"),
            )
        val result = repo.stream("123", 720)
        assertEquals("https://example.com/hevc", result.url)
        assertEquals("1080P（兼容资源）", result.quality)
    }

    @Test
    fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo =
            streamRepository(
                Triple(720, "bytevc2", "https://example.com/unsupported"),
                Triple(480, "h264", "https://example.com/avc"),
                Triple(1080, "bytevc1", "https://example.com/hevc"),
            )
        assertEquals("480P", repo.stream("123", 720).quality)
    }

    @Test
    fun entirelyIncompatibleSourceFailsBeforeStartingAudio() {
        val repo =
            streamRepository(
                Triple(720, "ByteVC2", "https://example.com/unsupported"),
                Triple(480, "bvc2", "https://example.com/unsupported2"),
            )
        assertThrows(IOException::class.java) { repo.stream("123", 720) }
    }

    @Test
    fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo =
            streamRepository(
                Triple(720, "", "https://example.com/legacy"),
                Triple(1080, "bytevc1", "https://example.com/hevc"),
            )
        assertEquals("720P", repo.stream("123", 720).quality)
    }

    @Test
    fun capabilityPredicateDropsUndecodableTiers() {
        val repo =
            streamRepository(
                Triple(1080, "bytevc1", "https://example.com/hevc"),
                Triple(480, "h264", "https://example.com/avc"),
            )
        val info = repo.stream("123", 1080) { it.codec == "h264" }
        assertEquals("480P", info.quality)
        assertEquals("https://example.com/avc", info.url)
    }

    @Test
    fun streamLadderCarriesCodedDimensionsAndCodecs() {
        val repo = repository { request ->
            if (request.url.host == "example.com") {
                val rows =
                    JSONObject()
                        .put(
                            "video_0",
                            JSONObject()
                                .put("quality_desc", "1080p")
                                .put("codec_type", "h264")
                                .put("vwidth", 1080)
                                .put("vheight", 1920)
                                .put("main_url", "https://example.com/portrait"),
                        )
                        .put(
                            "video_1",
                            JSONObject()
                                .put("quality_desc", "540p")
                                .put("codec_type", "h264")
                                .put("vwidth", 540)
                                .put("vheight", 960)
                                .put("main_url", "https://example.com/small"),
                        )
                JSONObject()
                    .put(
                        "video_info",
                        JSONObject().put("data", JSONObject().put("video_list", rows)),
                    )
                    .toString()
            } else
                """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
        }
        val ladder = repo.streamLadder("123")
        val portrait = ladder.renditions.first { it.url.endsWith("/portrait") }
        assertEquals("h264", portrait.codec)
        assertEquals(1080, portrait.width)
        assertEquals(1920, portrait.height)
        // A portrait 1080 tier is rejected by size while the smaller tier stays playable.
        assertEquals(
            540,
            ladder.select(1080) { !(it.width == 1080 && it.height == 1920) }!!.quality,
        )
    }

    private fun repository(
        clock: () -> Long = System::currentTimeMillis,
        respond: (Request) -> String,
    ) =
        ContentRepository(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(respond(chain.request()).toResponseBody())
                        .build()
                }
                .build(),
            clock,
        )

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) =
        JSONObject()
            .put("isSuccess", true)
            .put(
                "rankList",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("seriesId", "123")
                            .put("title", "测试漫剧")
                            .put("cover", "https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
                            .put("episodeCount", 12)
                            .put("tags", JSONArray().put("玄幻"))
                    ),
            )
            .put("pagination", JSONObject().put("pageNum", page).put("totalPages", totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun currentRank(payload: JSONObject) =
        script(
            "mergeLoaderData",
            JSONArray()
                .put("rank_hot-comic-drama/page")
                .put(
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("key", "content")
                                .put("routerDataFnName", "p")
                                .put("routerDataFnArgs", JSONArray().put(payload.toString()))
                        )
                ),
        )

    @Test
    fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload = rankPayload(2, 5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank", 23).put("heatText", "5746万热度")
        val repo = repository { request ->
            assertEquals("/rank/hot-comic-drama", request.url.encodedPath)
            assertEquals("2", request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>""" +
                currentRank(payload)
        }
        val result = repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23, "5746万热度"), result.ranking!!.positions["123"])
        assertEquals(5, result.ranking.totalPages)
        assertEquals("9月22日已更新", result.ranking.updatedText)
        assertTrue(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test
    fun missingRankIsNotInventedAndLastPageStops() {
        val result = repository { currentRank(rankPayload(5, 5)) }.comicRanking(5)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(null, ""), result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test
    fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo = repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=5">5</a></nav>"""
        }
        val result = repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21, "5746万热度"), result.ranking!!.positions["123"])
        assertEquals(5, result.ranking.totalPages)
        assertEquals("今日已更新", result.ranking.updatedText)
    }

    @Test
    fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo = repository { currentRank(rankPayload(1, 5)) }
        assertThrows(IOException::class.java) { repo.comicRanking(2) }
        assertThrows(IllegalArgumentException::class.java) { repo.comicRanking(0) }
    }

    @Test
    fun rankPageRetriesTransientFailureInsteadOfFailingTheBoard() {
        // CDN 软拦截表现为超时或空响应：同页一次有界重试应恢复读取。
        var calls = 0
        val repo = repository {
            _ ->
            calls += 1
            if (calls == 1) throw java.net.SocketTimeoutException("stalled stream")
            else currentRank(rankPayload(1, 2))
        }
        val result = repo.rankPage(RankBoard.COMIC)
        assertEquals(2, calls)
        assertEquals(listOf("123"), result.items.map { it.id })
    }

    @Test
    fun rankPageSurfacesPersistentFailureAfterOneRetry() {
        var calls = 0
        val repo = repository {
            _ ->
            calls += 1
            throw java.net.SocketTimeoutException("still stalled")
        }
        assertThrows(IOException::class.java) { repo.rankPage(RankBoard.ALL) }
        assertEquals(2, calls)
    }

    @Test
    fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val repo = repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result = repo.comicRanking(5)
        assertEquals(5, result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test
    fun comicHomeUsesDedicatedRankAndRealPagination() {
        val repo = repository { request ->
            assertEquals("/rank/hot-comic-drama", request.url.encodedPath)
            assertEquals("2", request.url.queryParameter("page"))
            currentRank(rankPayload(2, 2))
        }
        val page = repo.home(2, ContentType.COMIC)
        assertEquals("测试漫剧", page.items.single().title)
        assertEquals("https://example.com/cover.jpg", page.items.single().cover)
        assertEquals("全 12 集", page.items.single().badge)
        assertFalse(page.hasMore)
    }

    @Test
    fun legacyRankIsStillSupported() {
        val repo = repository {
            script(
                "r",
                JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload()),
            )
        }
        assertTrue(repo.home(1, ContentType.COMIC).hasMore)
    }

    @Test
    fun comicHomeDoesNotUseUnrelatedRankData() {
        val repo = repository {
            script(
                "r",
                JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload()),
            )
        }
        assertThrows(IOException::class.java) { repo.home(1, ContentType.COMIC) }
    }

    @Test
    fun rejectedComicRankRemainsAnError() {
        val repo = repository { currentRank(rankPayload().put("isSuccess", false)) }
        assertThrows(IOException::class.java) { repo.home(1, ContentType.COMIC) }
    }

    @Test
    fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo = repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page = repo.home(2, ContentType.COMIC)
        assertEquals("测试 & 漫剧", page.items.single().title)
        assertEquals("https://example.com/a.jpg", page.items.single().cover)
        assertTrue(page.hasMore)
    }

    @Test
    fun comicSearchCarriesCursorAndKeepsTypesAndQueriesSeparate() {
        val requests = mutableListOf<Request>()
        val repo = repository { request ->
            requests += request
            JSONObject()
                .put("code", 0)
                .put(
                    "search_tabs",
                    JSONArray()
                        .put(
                            searchTab(19, "999")
                                .put("search_id", "comic-session")
                                .put("passback", "comic-cursor")
                                .put("next_offset", 25)
                        ),
                )
                .toString()
        }
        repo.search("修仙", 1, ContentType.COMIC)
        repo.search("修仙", 2, ContentType.COMIC)
        assertEquals("comic-session", requests[1].url.queryParameter("search_id"))
        assertEquals("comic-cursor", requests[1].url.queryParameter("passback"))
        assertEquals("25", requests[1].url.queryParameter("offset"))
        assertThrows(SearchSessionExpiredException::class.java) {
            repo.search("重生", 2, ContentType.COMIC)
        }
        assertEquals(2, requests.size)
    }

    @Test
    fun expiredCursorRequestsFreshSearchInsteadOfDuplicateOffsetPage() {
        var now = 0L
        var requests = 0
        val repo =
            repository(clock = { now }) {
                requests++
                JSONObject()
                    .put("code", 0)
                    .put(
                        "search_tabs",
                        JSONArray()
                            .put(
                                searchTab(19, "999")
                                    .put("search_id", "session")
                                    .put("passback", "cursor")
                            ),
                    )
                    .toString()
            }
        repo.search("修仙", 1, ContentType.COMIC)
        now = 300001L
        assertThrows(SearchSessionExpiredException::class.java) {
            repo.search("修仙", 2, ContentType.COMIC)
        }
        assertEquals(1, requests)
        repo.search("修仙", 1, ContentType.COMIC)
        repo.search("修仙", 2, ContentType.COMIC)
        assertEquals(3, requests)
    }

    @Test
    fun shortHomeStillUsesCategoryAndStopsOnLastPage() {
        val repo = repository { request ->
            assertEquals("1", request.url.queryParameter("tab"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试短剧","genre":203}],"pagination":{"totalPages":1}}}};</script>"""
        }
        val result = repo.home(1, ContentType.SHORT)
        assertEquals("456", result.items.single().id)
        assertFalse(result.hasMore)
    }

    private fun searchTab(type: Int, id: String) =
        JSONObject()
            .put("tab_type", type)
            .put("has_more", true)
            .put(
                "data",
                JSONArray()
                    .put(
                        JSONObject()
                            .put(
                                "video_data",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("series_id", id)
                                            .put("title", "测试剧集")
                                            .put("episode_cnt", 8)
                                    ),
                            )
                    ),
            )

    @Test
    fun comicSearchRequestsAndSelectsOnlyComicTab() {
        var requests = 0
        val repo = repository { request ->
            requests++
            assertNotNull(request.header("x-gorgon"))
            assertEquals(
                VendorConstants.device["device_id"],
                request.url.queryParameter("device_id"),
            )
            assertEquals("19", request.url.queryParameter("tab_type"))
            assertEquals(if (requests == 1) "0" else "20", request.url.queryParameter("offset"))
            assertEquals("修仙", request.url.queryParameter("query"))
            JSONObject()
                .put("code", 0)
                .put("search_tabs", JSONArray().put(searchTab(11, "111")).put(searchTab(19, "999")))
                .toString()
        }
        repo.search("修仙", 1, ContentType.COMIC)
        assertEquals(listOf("999"), repo.search("修仙", 2, ContentType.COMIC).items.map { it.id })
    }

    @Test
    fun emptyComicSearchDoesNotFallBackToShortResults() {
        var requests = 0
        val repo = repository {
            requests++
            """{"code":0,"search_tabs":[{"tab_type":19,"data":[],"has_more":false}]}"""
        }
        assertTrue(repo.search("无结果", 1, ContentType.COMIC).items.isEmpty())
        assertEquals(1, requests)
    }

    @Test
    fun missingComicTabTriesRepairedGenreFilteredWebsiteAndReportsInvalidShape() {
        var requests = 0
        val repo = repository {
            requests++
            """{"code":0,"search_tabs":[{"tab_type":11,"data":[]}]}"""
        }
        assertThrows(IOException::class.java) { repo.search("修仙", 1, ContentType.COMIC) }
        assertEquals(2, requests)
    }

    @Test
    fun shortSearchKeepsWebsiteFallback() {
        val paths = mutableListOf<String>()
        val repo = repository { request ->
            paths += request.url.encodedPath
            if (paths.size == 1) throw IOException("offline")
            """{"searchList":[{"series_id":"456","series_name":"测试短剧","genre":203}]}"""
        }
        assertEquals("456", repo.search("短剧", 1, ContentType.SHORT).items.single().id)
        assertEquals(2, paths.size)
    }
}
