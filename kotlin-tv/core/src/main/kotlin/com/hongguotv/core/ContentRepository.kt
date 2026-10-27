// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

// 内容数据访问入口：封装搜索、详情与播放信息请求；界面层通过此处获取内容数据。

import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 站点 CDN 在重定向时下发 ttwid 并拦截无 Cookie 的后续请求，内存 Cookie 罐使其自动流转。 */
internal class MemoryCookies : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    // RFC 语义：同名 + 同域 + 同路径的新 Cookie 替换旧值，而不是叠加。
    private fun List<Cookie>.replacedBy(cookies: List<Cookie>) =
        filter { kept -> cookies.none { it.name == kept.name && it.domain == kept.domain && it.path == kept.path } }
            .toMutableList()
            .also { it += cookies }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        store.compute(url.host) { _, kept -> kept.orEmpty().replacedBy(cookies) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store[url.host].orEmpty().filter { it.expiresAt > now }
    }
}

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()

internal fun cleanUrl(s: String) =
    s.replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&")
        .replace("&amp;", "&")

data class Series(
    val id: String,
    val title: String,
    val cover: String = "",
    val description: String = "",
    val badge: String = "",
    val tags: String = "",
) {
    fun toJson() =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("cover", cover)
            .put("description", description)
            .put("badge", badge)
            .put("tags", tags)

    companion object {
        fun fromJson(o: JSONObject) =
            Series(
                o.str("id"),
                o.str("title"),
                o.str("cover"),
                o.str("description"),
                o.str("badge"),
                o.str("tags"),
            )
    }
}

data class Detail(val series: Series, val episodes: List<String>)

data class RankPosition(val rank: Int?, val heat: String, val metrics: String = "")

data class ComicRanking(
    val positions: Map<String, RankPosition>,
    val totalPages: Int,
    val updatedText: String,
)

data class TrialHint(val episodeId: String, val episodeCount: Int)

data class CatalogPage(
    val items: List<Series>,
    val hasMore: Boolean,
    val ranking: ComicRanking? = null,
    val discovery: DiscoveryInfo? = null,
    val trialHints: Map<String, TrialHint> = emptyMap(),
)

data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)

/** One playable rendition; width/height are the coded dimensions when the API reports them. */
data class Rendition(
    val quality: Int,
    val codec: String,
    val width: Int?,
    val height: Int?,
    val url: String,
    val key: ByteArray?,
) {
    fun toStreamInfo() = StreamInfo(url, key, if (quality > 0) "${quality}P" else "自动")
}

/**
 * All playable renditions of one episode. Capability-first selection: TV boxes commonly cap AVC
 * decoders at 1920x1088 with width/height checked independently, rejecting portrait sizes.
 */
class StreamLadder(val renditions: List<Rendition>) {
    // Ascending order: a greater element is the better tier (higher quality, h264 over others).
    // maxWithOrNull picks the best tier; minWithOrNull yields the closest-below fallback.
    private val better =
        compareBy<Rendition> { it.quality }.thenBy { if (it.codec in AVC_LABELS) 1 else 0 }

    fun select(maxQuality: Int, supported: (Rendition) -> Boolean = { true }): Rendition? {
        val pool = renditions.filter(supported).ifEmpty { renditions }
        return pool.filter { it.quality in 1..maxQuality }.maxWithOrNull(better)
            ?: pool.filter { it.quality <= 0 }.maxWithOrNull(better)
            ?: pool.minWithOrNull(better)
    }

    /** select() first, then every lower or equal tier best-first for verified downgrade. */
    fun playbackOrder(
        maxQuality: Int,
        supported: (Rendition) -> Boolean = { true },
    ): List<Rendition> {
        val pick = select(maxQuality, supported) ?: return emptyList()
        val alternates =
            renditions
                .filter { it !== pick && it.quality <= pick.quality }
                .sortedWith(better.reversed())
        return listOf(pick) + alternates
    }

    companion object {
        private val AVC_LABELS = setOf("h264", "avc", "avc1", "avc3")
    }
}

class ContentHttpException(val statusCode: Int) : IOException("内容请求失败（HTTP $statusCode）")

/** Only identified incompatible video codecs qualify for an optional episode skip. */
class IncompatibleStreamException : IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")

class SearchSessionExpiredException : IOException("搜索结果已过期，请从第 1 页重新搜索")

class ContentRepository(
    val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .cookieJar(MemoryCookies())
            .build(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val signer = Signer()

    // 榜单页偶发被 CDN 软拦截（空响应或挂起到超时）：收紧超时并单次重试，
    // 让整榜失败在最坏约 15 秒内暴露，而不是分钟级的全局超时。
    private val rankHttp: OkHttpClient by lazy {
        http.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val site = "https://hongguoduanju.com"
    private val suggestionWebId = UUID.randomUUID().toString()
    private data class SearchCursor(
        val searchId: String,
        val passback: String,
        val offset: Int?,
        val updatedAt: Long,
    )

    private val searchCursors = linkedMapOf<Triple<ContentType, String, Int>, SearchCursor>()
    private val webSearchQueries=linkedMapOf<Pair<ContentType,String>,Long>()
    @Volatile private var appSearchRetryAt=0L

    // 构造并执行内容请求，统一检查 HTTP 状态和响应大小；仅按规则重试临时故障。
    private fun text(
        url: String,
        signed: Signer.Request? = null,
        retryTransient: Boolean = false,
        client: OkHttpClient = http,
    ): String {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", VendorConstants.VIDEO_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k, v) -> request.header(k, v) }
        signed?.body?.let {
            request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        fun fetch() =
            RequestScope.execute(client.newCall(request.build())) { response ->
                if (!response.isSuccessful) throw ContentHttpException(response.code)
                val body = response.body ?: throw IOException("内容响应为空")
                if (body.contentLength() > 16 * 1024 * 1024) throw IOException("内容响应过大")
                val input = body.byteStream()
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (out.size() <= 16 * 1024 * 1024) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                val bytes = out.toByteArray()
                if (bytes.size > 16 * 1024 * 1024) throw IOException("内容响应过大")
                if(bytes.isEmpty()) throw IOException("内容接口返回空响应，请稍后重试")
                String(bytes, Charsets.UTF_8)
            }
        try {
            return fetch()
        } catch (e: IOException) {
            val temporary =
                e is java.net.SocketTimeoutException ||
                    e is java.net.SocketException ||
                    e is java.net.UnknownHostException ||
                    (e is ContentHttpException && e.statusCode in setOf(500, 502, 503, 504))
            if (!retryTransient || !temporary || Thread.currentThread().isInterrupted) throw e
            // One bounded retry of the same read-only search page; navigation still cancels its
            // scope.
            try {
                Thread.sleep(700)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("请求已取消")
            }
            return fetch()
        }
    }

    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")

    private fun card(item: JSONObject): Series {
        val o = item.optJSONObject("video_data") ?: item
        val count =
            o.optInt(
                "episode_cnt",
                o.optJSONObject("series_episode_info")?.optInt("episode_cnt", 0) ?: 0,
            )
        val tags = o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(
            o.str("series_id"),
            o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },
            cleanUrl(o.str("series_cover")),
            o.str("series_intro"),
            o.str("episode_right_text").ifEmpty { if (count > 0) "全 ${count} 集" else "" },
            (0 until minOf(tags.length(), 5)).joinToString(" · ") {
                tags.optJSONObject(it)?.str("name") ?: tags.optString(it)
            },
        )
    }

    private fun valid(rows: List<Series>) =
        rows
            .filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }
            .distinctBy { it.id }

    fun comicRanking(page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/hot-comic-drama?page=$page"), page)
    }

    fun rankPage(board: RankBoard, page: Int = 1): CatalogPage {
        require(page in 1..100)
        // 榜单走收紧超时的专用客户端并重试一次瞬时失败；ttwid 由内存 Cookie 罐自动携带。
        val body =
            text(
                "$site/rank/${board.route}?page=$page",
                retryTransient = true,
                client = rankHttp,
            )
        return ComicRank.parse(body, page, board)
    }

    fun rankSnapshot(board: RankBoard): RankSnapshot {
        val first = rankPage(board)
        val meta = first.ranking ?: throw IOException("榜单信息缺失")
        // Public boards currently expose 100 entries across five pages. Never claim
        // a complete board if the upstream size changes beyond this bounded request.
        if (meta.totalPages > 5) throw IOException("榜单页数已变化，请稍后重试")
        val rows = first.items.toMutableList()
        val positions = meta.positions.toMutableMap()
        for (page in 2..meta.totalPages) {
            val next = rankPage(board, page)
            if (next.ranking?.totalPages != meta.totalPages) throw IOException("榜单正在更新，请刷新重试")
            rows += next.items
            positions.putAll(next.ranking.positions)
        }
        return RankSnapshot(board, rows.distinctBy { it.id }, meta.copy(positions = positions))
    }

    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        if (type == ContentType.COMIC) return comicRanking(page)
        val data = router("$site/category?tab=1&sort_type=1" + if (page > 1) "&page=$page" else "")
        val section =
            data.optJSONObject("category_page")
                ?: data.optJSONObject("category_$")
                ?: throw IOException("首页数据结构已变化")
        val rows =
            section.optJSONArray("recommendList")
                ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList")
                ?: throw IOException("首页数据暂不可用")
        val items = valid(rows.objects().map(::card))
        val lastPage = section.optJSONObject("pagination")?.optInt("totalPages", 100) ?: 100
        return CatalogPage(items, items.isNotEmpty() && page < minOf(lastPage, 100))
    }

    fun filteredDiscovery(
        selection: DiscoverySelection,
        cursor: DiscoveryCursor? = null,
    ): FilteredDiscoveryPage {
        val values = VendorConstants.device.toMutableMap()
        for (key in
            listOf(
                "version_code",
                "manifest_version_code",
                "update_version_code",
                "pv_player",
            )) values[key] = "73532"
        values["version_name"] = "7.3.5.32"
        val signed =
            signer.sign(
                "https://api5-normal-sinfonlineb.fqnovel.com/reading/distribution/category/landpage/v/",
                values,
                selection.request(cursor).toString().toByteArray(Charsets.UTF_8),
            )
        return FilteredDiscoveryParser.parse(
            text(signed.url, signed, retryTransient = true),
            cursor,
        )
    }

    fun discover(category: DramaCategory, topic: String = "", page: Int = 1): CatalogPage {
        require(
            page in 1..100 && (topic.isEmpty() || topic.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")))
        )
        val path = "/category/${category.route}" + if (topic.isBlank()) "" else "/$topic"
        val data = router("$site$path?page=$page")
        val section =
            data.optJSONObject("category_$")
                ?: data.optJSONObject("category_page")
                ?: throw IOException("分类找剧数据已变化")
        val info = DiscoveryParser.info(section, category, topic, page)
        val rows = section.optJSONArray("recommendList") ?: throw IOException("分类剧集列表缺失")
        val items = valid(rows.objects().map(::card))
        if (rows.length() > 0 && items.isEmpty()) throw IOException("分类剧集格式已变化")
        return CatalogPage(items, items.isNotEmpty() && page < info.totalPages, discovery = info)
    }

    // 按内容类型执行关键词搜索；分页状态由对应的搜索实现维护。
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        val key=type to keyword
        val useWebsite=synchronized(webSearchQueries) {
            webSearchQueries.entries.removeAll { clock()-it.value>600_000 }
            if(page==1) webSearchQueries.remove(key)
            key in webSearchQueries
        }
        if(page>1 && !useWebsite) {
            val previous=synchronized(searchCursors) { searchCursors[Triple(type,keyword,page-1)] }
            if(previous==null || clock()-previous.updatedAt>300_000 || clock()<appSearchRetryAt)
                throw SearchSessionExpiredException()
        }
        if(!useWebsite && clock()>=appSearchRetryAt) {
            try {
                val result=appSearch(keyword,page,type)
                if(result.items.isNotEmpty() || !result.hasMore) return result
            } catch(e: Exception) {
                if(e is SearchSessionExpiredException || Thread.currentThread().isInterrupted) throw e
                // An empty/rejected App response is not a network outage. Avoid repeating it per query.
                appSearchRetryAt=clock()+300_000
                if(page>1) throw SearchSessionExpiredException()
            }
        }
        if(page>1 && !useWebsite) {
            appSearchRetryAt=clock()+300_000
            throw SearchSessionExpiredException()
        }
        val result=webSearch(keyword,page,type)
        synchronized(webSearchQueries) {
            webSearchQueries[key]=clock()
            while(webSearchQueries.size>100) webSearchQueries.remove(webSearchQueries.keys.first())
        }
        return result
    }
    private fun webSearch(keyword: String,page: Int,type: ContentType): CatalogPage {
        val url="$site/api/search/result/?query=${Signer.encode(keyword)}&page=$page"
        val body=try { text(url) } catch(e: ContentHttpException) {
            if(e.statusCode !in 502..504 || Thread.currentThread().isInterrupted) throw e
            text(url) // One bounded retry for a transient gateway error; never retry malformed data.
        }
        val data=try { JSONObject(body) } catch(e: org.json.JSONException) { throw IOException("搜索数据格式异常，请稍后重试",e) }
        val rows=data.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        if(data.optInt("pageNum",page)!=page) throw IOException("搜索分页信息异常，请重新搜索")
        val genre=if(type==ContentType.COMIC) "205" else "203"
        val items=valid(rows.objects().filter { (it.optJSONObject("video_data") ?: it).str("genre")==genre }.map(::card))
        val lastPage=data.optInt("totalPages",page)
        // A genre-filtered page can be empty while subsequent source pages still contain matches.
        return CatalogPage(items,data.optBoolean("hasMore",false) && page<minOf(lastPage,100))
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        val values=VendorConstants.device.toMutableMap()
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[k]="72232"
        values["version_name"]="7.2.2.32"
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to type.searchTab.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        if(type==ContentType.COMIC && page>1 && cursor==null) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",values)
        val data=JSONObject(text(signed.url,signed))
        if(data.optInt("code",-1)!=0) throw IOException("搜索接口暂不可用")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { cells.addAll(it) }
            for(row in cells) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count=video.optInt("episode_cnt")
                list+=Series(id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock())
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }

    /** Anonymous first-party website suggestions; pinyin matching is provided by the server. */
    // 获取服务端搜索联想，供遥控搜索输入框展示候选词。
    fun searchSuggestions(keyword: String): List<String> {
        val clean = keyword.trim()
        if (clean.isEmpty()) return emptyList()
        require(clean.length <= 80)
        val raw =
            text(
                "$site/incent_resource/suggestion?app_id=8662&web_id=$suggestionWebId&query=${Signer.encode(clean)}&count=8"
            )
        val rows = JSONObject(raw).optJSONArray("suggest_list") ?: throw IOException("搜索联想暂不可用")
        return rows
            .objects()
            .map {
                it.str("name").ifBlank {
                    it.optJSONArray("display_words")?.optString(0).orEmpty().trim()
                }
            }
            .filter { it.isNotBlank() && it.length <= 80 }
            .distinct()
            .take(8)
    }

    /** The public website now loads cross-category results through its JSON endpoint. */
    fun searchAll(keyword: String, page: Int = 1): CatalogPage = searchAllPage(keyword, page)

    private fun searchAllPage(keyword: String, page: Int, preserveMore: Boolean = false): CatalogPage {
        require(keyword.isNotBlank() && keyword.length <= 80 && page in 1..100)
        val raw =
            text(
                "$site/api/search/result?query=${Signer.encode(keyword)}&page=$page",
                retryTransient = true,
            )
        val section =
            try {
                JSONObject(raw)
            } catch (_: Exception) {
                throw IOException("搜索响应格式已变化，请稍后重试")
            }
        if (section.has("pageNum") && section.optInt("pageNum") != page)
            throw IOException("搜索页码不匹配，请重新搜索")
        val rows = section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val items = valid(rows.objects().map(::card))
        val more =
            when {
                section.has("hasMore") -> section.optBoolean("hasMore")
                section.optInt("totalPages") > 0 -> page < section.optInt("totalPages")
                else -> preserveMore || page < 100
            }
        return CatalogPage(items, if (preserveMore) more else items.isNotEmpty() && page < 100 && more)
    }

    private val relatedCache = linkedMapOf<String, List<RelatedWork>>()

    private data class RelatedSearch(
        val official: List<RelatedWork>,
        val officialMissing: Boolean,
        val pages: MutableMap<Int, RelatedWorksPage> = linkedMapOf(),
        var updatedAt: Long,
    )
    private val relatedSearchCache = linkedMapOf<Pair<String, String>, RelatedSearch>()
    // Independent search state: opening a detail must never reset the catalog's live cursor.
    private val seasonSearch by lazy { ContentRepository(http, clock) }

    /** One explicit website search page per invocation, never invoked by favorite monitoring. */
    fun relatedComplete(current: Series, page: Int = 1, force: Boolean = false): RelatedWorksPage {
        require(current.id.matches(Regex("[0-9]{1,30}")) && page in 1..100)
        val key = current.id to current.title
        val query = SeasonFamilies.keyword(current)
        var state = synchronized(relatedSearchCache) {
            relatedSearchCache.entries.removeAll { clock() - it.value.updatedAt > 600_000 }
            if (force && page == 1) relatedSearchCache.remove(key)
            relatedSearchCache[key]
        }
        state?.pages?.get(page)?.let { return it }
        if (page > 1 && (state == null || state.pages[page - 1]?.nextPage != page))
            throw SearchSessionExpiredException()
        if (state == null) {
            var officialMissing = false
            val official = try { related(current.id, force) } catch (failure: Exception) {
                rethrowCancellation(failure)
                officialMissing = true
                emptyList()
            }
            state = RelatedSearch(official, officialMissing, updatedAt = clock())
            synchronized(relatedSearchCache) {
                while (relatedSearchCache.size >= 8) relatedSearchCache.remove(relatedSearchCache.keys.first())
                relatedSearchCache[key] = state
            }
        }
        val active = state
        val before = active.pages[page - 1]?.items ?: active.official
        if (query.isBlank()) return RelatedWorksPage(before, null, false, "片名缺少可搜索的主体，仅显示官方相关作品")
        val searched = try { seasonSearch.searchAllPage(query, page, preserveMore = true) } catch (failure: Exception) {
            rethrowCancellation(failure)
            return RelatedWorksPage(before, page, false,
                (if (active.officialMissing) "官方相关作品未读取；" else "") + "同名搜索第${page}页未完成，可重试，当前列表不完整")
        }
        val items = SeasonFamilies.merge(current, before, searched.items)
        val more = searched.hasMore
        val complete = !more
        val message = (if (active.officialMissing) "官方相关作品未读取；" else "") + when {
            more && page < 100 -> "同名搜索已读第${page}页，仍有更多结果；搜索匹配需手动核对"
            more -> "同名搜索达到100页上限，列表可能不完整；搜索匹配需手动核对"
            else -> "同名搜索已读完，可能存在不同命名的续作；搜索匹配需手动核对"
        }
        val result = RelatedWorksPage(items, if (more && page < 100) page + 1 else null, complete, message)
        active.pages[page] = result
        active.updatedAt = clock()
        return result
    }

    private fun rethrowCancellation(failure: Exception) {
        if (Thread.currentThread().isInterrupted ||
            (failure is java.io.InterruptedIOException && failure !is java.net.SocketTimeoutException) ||
            failure is java.util.concurrent.CancellationException ||
            failure.message in setOf("Canceled", "请求已取消")) throw failure
    }

    fun related(id: String, force: Boolean = false): List<RelatedWork> {
        require(id.matches(Regex("[0-9]{1,30}")))
        if (!force)
            synchronized(relatedCache) {
                relatedCache[id]?.let {
                    return it
                }
            }
        var failure: Exception? = null
        val values =
            VendorConstants.device.toMutableMap().apply {
                for (key in
                    listOf(
                        "version_code",
                        "manifest_version_code",
                        "update_version_code",
                        "pv_player",
                    )) put(key, "72232")
                put("version_name", "7.2.2.32")
                putAll(
                    mapOf(
                        "scene" to "10",
                        "from" to "detail_page_more_related",
                        "book_id" to id,
                        "new_search_middle_page" to "false",
                        "bookstore_tab_type" to "0",
                        "query_history_removed" to "false",
                        "user_is_login" to "0",
                        "bookstore_tab" to "0",
                        "search_middle_page_version" to "0",
                        "pad_column_detail" to "0",
                        "hot_word_exchange" to "false",
                        "device_level" to "3",
                        "limit" to "0",
                        "offset" to "0",
                        "is_horizontal_screen" to "false",
                        "reader_single_col_from_ip_strategy" to "false",
                    )
                )
            }
        for (host in
            listOf(
                "api5-normal-sinfonlinec.fqnovel.com",
                "api5-normal-hl.fqnovel.com",
                "api5-normal-lf.fqnovel.com",
            )) {
            try {
                val request = signer.sign("https://$host/reading/bookapi/plan/v:version/", values)
                val raw = text(request.url, request)
                if (raw.isBlank()) {
                    failure = IOException("相关作品接口暂无响应")
                    continue
                }
                val rows = RelatedWorks.parse(raw, id)
                synchronized(relatedCache) {
                    if (relatedCache.size >= 8) relatedCache.remove(relatedCache.keys.first())
                    relatedCache[id] = rows
                }
                return rows
            } catch (problem: Exception) {
                rethrowCancellation(problem)
                failure = problem
            }
        }
        throw IOException("相关作品暂时无法加载，请重试", failure)
    }

    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val o =
            router("$site/detail?series_id=$id")
                .optJSONObject("detail_page")
                ?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw = o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes = (0 until raw.length()).map { raw.getString(it) }
        if (
            episodes.isEmpty() ||
                episodes.any { !it.matches(Regex("[0-9]{1,30}")) } ||
                episodes.distinct().size != episodes.size
        )
            throw IOException("分集数据异常")
        return Detail(card(o).copy(id = id), episodes)
    }

    fun stream(
        id: String,
        maxQuality: Int = 1080,
        supported: (Rendition) -> Boolean = { true },
    ): StreamInfo {
        val selected =
            streamLadder(id).select(maxQuality, supported)
                ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val info = selected.toStreamInfo()
        return if (selected.quality > maxQuality) info.copy(quality = info.quality + "（兼容资源）")
        else info
    }

    /** Every playable rendition of one episode, best tier first; one player-API round trip. */
    fun streamLadder(id: String): StreamLadder {
        require(id.matches(Regex("[0-9]{1,30}")))
        val payload =
            """{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}"""
                .toByteArray()
        val request=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1/",VendorConstants.device,payload)
        val playerUrl=request.url
        val response = text(playerUrl, request)
        if (response.isBlank()) throw IOException("播放接口返回空响应，请稍后重试或更新应用")
        val data = JSONObject(response)
        val code = data.optInt("Code", data.optInt("code", 0))
        if (code != 0) throw IOException("播放接口暂不可用（错误码 $code）")
        val map = data.optJSONObject("data") ?: throw IOException("播放信息为空")
        val entry =
            map.optJSONObject(id) ?: throw IOException("播放接口未返回当前分集")
        val model = entry.optJSONObject("video_model") ?: JSONObject(entry.str("video_model"))
        // The current model already carries plain media URLs, dimensions and CENC keys.
        // Only use the legacy fallback endpoint when this shape has no usable rendition.
        val inlineArray = model.optJSONArray("video_list")
        val inlineRows = inlineArray?.objects().orEmpty()
        val inline =
            inlineRows.mapNotNull { row ->
                runCatching {
                        val meta = row.optJSONObject("video_meta") ?: return@runCatching null
                        val encryption =
                            row.optJSONObject("encrypt_info") ?: return@runCatching null
                        val codec = meta.str("codec_type").lowercase()
                        val url = cleanUrl(row.str("main_url"))
                        if (
                            codec.isBlank() ||
                                codec in setOf("bytevc2", "bvc2") ||
                                (!url.startsWith("https://") && !url.startsWith("http://"))
                        )
                            return@runCatching null
                        val width = dimension(meta, "vwidth", "width") ?: return@runCatching null
                        val height = dimension(meta, "vheight", "height") ?: return@runCatching null
                        val key =
                            encryption
                                .str("spade_a")
                                .takeIf(String::isNotBlank)
                                ?.let(MediaCrypto::deriveKey)
                        if (
                            key == null &&
                                encryption.opt("encrypt") !in listOf(false, 0, "0", "false")
                        )
                            return@runCatching null
                        Rendition(
                            qualityNumber(meta.str("definition")),
                            codec,
                            width,
                            height,
                            url,
                            key,
                        )
                    }
                    .getOrNull()
            }
        if (inline.isNotEmpty()) return StreamLadder(inline)
        val raw = model.opt("fallback_api")
        val fallback =
            when (raw) {
                null, JSONObject.NULL -> ""
                is JSONObject -> raw.str("fallback_api")
                is JSONArray -> raw.optString(0)
                else ->
                    runCatching { JSONObject(raw.toString()).str("fallback_api") }
                        .getOrDefault(raw.toString())
            }
        if (!fallback.startsWith("https://")) {
            if (inlineRows.isNotEmpty() && inlineRows.size == inlineArray?.length() && inlineRows.all { row ->
                val url = cleanUrl(row.str("main_url"))
                val meta = row.optJSONObject("video_meta")
                val encryption = row.optJSONObject("encrypt_info")
                meta != null && encryption != null &&
                    meta.str("codec_type").lowercase() in setOf("bytevc2", "bvc2") &&
                    dimension(meta, "vwidth", "width") != null && dimension(meta, "vheight", "height") != null &&
                    (url.startsWith("https://") || url.startsWith("http://")) &&
                    (encryption.opt("encrypt") in listOf(false, 0, "0", "false") ||
                        runCatching { MediaCrypto.deriveKey(encryption.str("spade_a")) }.isSuccess)
            }) throw IncompatibleStreamException()
            throw IOException("播放地址格式异常")
        }
        val info =
            JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data")
                ?: throw IOException("没有播放资源")
        val videoList = info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4
        // extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val rawRows = videoList.keys().asSequence().mapNotNull { videoList.optJSONObject(it) }.toList()
        val rows =
            videoList
                .keys()
                .asSequence()
                .mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }
                .filter {
                    it.second.str("main_url").isNotEmpty() &&
                        it.second.str("codec_type").lowercase() !in setOf("bytevc2", "bvc2")
                }
                .map { (k, o) ->
                    Triple(
                        qualityNumber(
                            o.str("quality_desc").ifEmpty {
                                o.str("height").ifEmpty {
                                    o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } }
                                }
                            }
                        ),
                        k,
                        o,
                    )
                }
                .sortedByDescending { it.first }
                .toList()
        val seed = info.str("key_seed")
        // A broken rendition entry must not take down otherwise playable siblings.
        val renditions =
            rows.mapNotNull { (quality, _, o) ->
                runCatching {
                        var url = cleanUrl(o.str("main_url"))
                        if (seed.isNotEmpty()) url = MediaCrypto.decryptUrl(url, decode64(seed))
                        if (!url.startsWith("https://") && !url.startsWith("http://"))
                            return@runCatching null
                        Rendition(
                            quality,
                            o.str("codec_type").lowercase(),
                            dimension(o, "vwidth", "width"),
                            dimension(o, "vheight", "height"),
                            url,
                            o.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey),
                        )
                    }
                    .getOrNull()
            }
        if (renditions.isEmpty()) {
            if (rawRows.isNotEmpty() && rawRows.size == videoList.length() && rawRows.all {
                it.str("codec_type").lowercase() in setOf("bytevc2", "bvc2") && runCatching {
                    val url = if (seed.isBlank()) cleanUrl(it.str("main_url"))
                        else MediaCrypto.decryptUrl(cleanUrl(it.str("main_url")), decode64(seed))
                    url.startsWith("https://") || url.startsWith("http://")
                }.getOrDefault(false)
            }) throw IncompatibleStreamException()
            throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        }
        return StreamLadder(renditions)
    }

    private fun dimension(o: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            val value = o.optInt(key, 0)
            if (value > 0) return value
        }
        return null
    }

    companion object {
        fun extractRouter(html: String): JSONObject {
            val marker = html.indexOf("_ROUTER_DATA")
            if (marker < 0) throw IOException("页面没有内容数据")
            val first = html.indexOf('{', marker)
            if (first < 0) throw IOException("页面数据格式错误")
            var depth = 0
            var quoted = false
            var escaped = false
            for (i in first until html.length) {
                val ch = html[i]
                if (quoted) {
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
                    continue
                }
                when (ch) {
                    '"' -> quoted = true
                    '{',
                    '[' -> depth++
                    '}',
                    ']' -> {
                        depth--
                        if (depth == 0) return JSONObject(html.substring(first, i + 1))
                    }
                }
            }
            throw IOException("页面数据不完整")
        }

        fun qualityNumber(value: String): Int =
            Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt()
                ?: mapOf("1920" to 1080, "1280" to 720, "1024" to 576, "854" to 480, "640" to 360)[
                    value]
                ?: value.toIntOrNull()
                ?: 0
    }
}
