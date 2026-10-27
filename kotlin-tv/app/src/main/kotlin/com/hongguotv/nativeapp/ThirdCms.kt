// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ThirdSource(val id: String, val name: String, val api: String)
data class ThirdCategory(val id: String, val name: String)
data class ThirdVideo(val id: String, val name: String, val cover: String = "", val remarks: String = "")
data class ThirdEpisode(val label: String, val url: String)
data class ThirdDetail(val video: ThirdVideo, val synopsis: String, val groups: List<Pair<String, List<ThirdEpisode>>>)
data class ThirdListPage(val categories: List<ThirdCategory>, val videos: List<ThirdVideo>, val page: Int, val hasMore: Boolean)
data class ThirdSearchHit(val source: ThirdSource, val video: ThirdVideo)
data class ThirdSearchGroup(val title: String, val hits: List<ThirdSearchHit>)
data class ThirdAggregateResult(val groups: List<ThirdSearchGroup>, val successfulSources: Int, val totalSources: Int,
    val page: Int = 1, val nextSourceIds: List<String> = emptyList(), val failedSourceIds: List<String> = emptyList(),
    val measurements: Map<String, SourceMeasurement> = emptyMap())

object ThirdCms {
    const val MAX_SOURCES = 30
    const val MAX_BYTES = 2 * 1024 * 1024
    fun mergeAggregates(previous: ThirdAggregateResult, next: ThirdAggregateResult): ThirdAggregateResult = next.copy(
        groups = groupSearchHits(previous.groups.flatMap { it.hits } + next.groups.flatMap { it.hits }),
        totalSources = previous.totalSources, failedSourceIds = (previous.failedSourceIds + next.failedSourceIds).distinct())
    fun groupSearchHits(hits: List<ThirdSearchHit>): List<ThirdSearchGroup> = hits
        .distinctBy { it.source.id to it.video.id }.take(1000)
        .groupBy { it.video.name.replace(Regex("[\\s《》]+"), "").lowercase(java.util.Locale.ROOT) }
        .values.map { ThirdSearchGroup(it.first().video.name, it) }
    /** User-supplied HTTP(S), including home-LAN servers; no scripts, user-info or URL fragments. */
    fun validUrl(url: String): Boolean {
        if (url.length !in 1..4096 || url.any(Char::isISOControl)) return false
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.fragment == null
    }
    fun validApi(url: String): Boolean = validUrl(url) && url.toHttpUrlOrNull()!!.pathSegments.lastOrNull().orEmpty()
        .lowercase(java.util.Locale.ROOT).let { path -> listOf(".jar", ".js", ".mjs", ".dex", ".so", ".class").none(path::endsWith) }
    fun apiUrl(api: String, params: List<Pair<String, String>>): String {
        require(validApi(api)) { "接口地址须为 HTTP(S) JSON，不支持脚本或插件文件" }
        return api.toHttpUrlOrNull()!!.newBuilder().apply {
            params.forEach { (key, value) -> setQueryParameter(key, value) }
        }.build().toString()
    }
    /** Compatible MacCMS/TVBox type=1 configs only. No jar, JavaScript or encrypted source code. */
    fun parseSources(body: String): List<ThirdSource> {
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "配置超过 2 MB" }
        val trimmed = body.trim()
        val rows = if (trimmed.startsWith("[")) JSONArray(trimmed) else {
            val root = JSONObject(trimmed)
            root.optJSONArray("sites") ?: root.optJSONArray("sources") ?: throw IllegalArgumentException("配置缺少 sites / sources 数组")
        }
        require(rows.length() <= 1000) { "配置条目过多" }
        val result = linkedMapOf<String, ThirdSource>()
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            if (item.has("type") && item.optInt("type", -1) != 1) continue
            val api = item.optString("api").trim()
            if (!validApi(api) || result.containsKey(api)) continue
            val name = clean(item.optString("name"), 100).ifBlank { api.toHttpUrlOrNull()!!.host }
            result[api] = ThirdSource(UUID.nameUUIDFromBytes(api.toByteArray(Charsets.UTF_8)).toString(), name, api)
            if (result.size >= MAX_SOURCES) break
        }
        return result.values.toList()
    }
    /** Protocol layout adapted from jycao2's public ThirdCms; validated before the player sees URLs. */
    fun parsePlayGroups(from: String, urls: String): List<Pair<String, List<ThirdEpisode>>> {
        val names = from.split("$$$")
        return urls.split("$$$").take(20).mapIndexedNotNull { groupIndex, group ->
            val episodes = group.split('#').take(2000).mapIndexedNotNull { index, entry ->
                val divider = entry.indexOf('$')
                val url = (if (divider >= 0) entry.substring(divider + 1) else entry).trim()
                if (!validUrl(url)) null else ThirdEpisode(
                    (if (divider > 0) clean(entry.substring(0, divider), 100) else "").ifBlank { "第${index + 1}集" }, url)
            }
            if (episodes.isEmpty()) null else clean(names.getOrNull(groupIndex).orEmpty(), 100).ifBlank { "线路${groupIndex + 1}" } to episodes
        }
    }
    private fun clean(text: String, max: Int = 300) = text.replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("[\\p{Cntrl}]+"), " ").trim().take(max)
    private fun root(body: String): JSONObject {
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "响应超过 2 MB" }
        val root = JSONObject(body)
        require(root.optJSONArray("list") != null) { "源站响应缺少 list 数组" }
        return root
    }
    private fun video(item: JSONObject): ThirdVideo? {
        val id = item.optString("vod_id").trim().take(128)
        val name = clean(item.optString("vod_name"), 200)
        if (id.isEmpty() || name.isEmpty()) return null
        val cover = item.optString("vod_pic").trim().takeIf(::validUrl).orEmpty()
        return ThirdVideo(id, name, cover, clean(item.optString("vod_remarks"), 100))
    }
    fun parseList(body: String): ThirdListPage {
        val root = root(body)
        val categories = root.optJSONArray("class")?.let { rows ->
            (0 until minOf(rows.length(), 100)).mapNotNull { i ->
                rows.optJSONObject(i)?.let { item ->
                    val id = item.optString("type_id").trim().take(128)
                    val name = clean(item.optString("type_name"), 100)
                    if (id.isBlank() || name.isBlank()) null else ThirdCategory(id, name)
                }
            }
        }.orEmpty()
        val rows = root.getJSONArray("list")
        val videos = (0 until minOf(rows.length(), 200)).mapNotNull { i -> rows.optJSONObject(i)?.let(::video) }.distinctBy { it.id }
        val page = root.optInt("page", 1).coerceAtLeast(1)
        val pages = root.optInt("pagecount")
        return ThirdListPage(categories, videos, page, videos.isNotEmpty() && (if (pages > 0) page < pages else rows.length() >= 20))
    }
    fun parseDetail(body: String): ThirdDetail? {
        val rows = root(body).getJSONArray("list")
        for (index in 0 until minOf(rows.length(), 200)) {
            val item = rows.optJSONObject(index) ?: continue
            val video = video(item) ?: continue
            return ThirdDetail(video, clean(item.optString("vod_content"), 4000),
                parsePlayGroups(item.optString("vod_play_from"), item.optString("vod_play_url")))
        }
        return null
    }
}

/** Independent unsigned GET requests; starts only following a source-screen action. */
class ThirdCmsClient {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private val calls = java.util.Collections.synchronizedSet(mutableSetOf<Call>())
    private val searches = java.util.Collections.synchronizedSet(mutableSetOf<java.util.concurrent.ExecutorService>())
    private val cancellation = java.util.concurrent.atomic.AtomicLong()
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    fun cancel() {
        cancellation.incrementAndGet()
        synchronized(searches) { searches.toList().forEach { it.shutdownNow() } }
        synchronized(calls) { calls.toList().forEach(Call::cancel); calls.clear() }
    }
    fun close() { closed.set(true); cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    private fun get(url: String, token: Long): String {
        require(ThirdCms.validUrl(url))
        val call = http.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        synchronized(calls) {
            if (closed.get() || Thread.currentThread().isInterrupted || cancellation.get() != token) throw IOException("请求已取消")
            calls += call
        }
        try {
            return call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("源站返回空响应")
                if (body.contentLength() > ThirdCms.MAX_BYTES) throw IOException("源站响应过大")
                val bytes = body.byteStream().use { input ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        if (output.size() + count > ThirdCms.MAX_BYTES) throw IOException("源站响应过大")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val utf8 = String(bytes, Charsets.UTF_8)
                if (utf8.contains('\uFFFD')) String(bytes, charset("GBK")) else utf8
            }
        } finally { calls -= call }
    }
    fun list(source: ThirdSource, page: Int = 1, category: String? = null, query: String? = null): ThirdListPage {
        return listAt(source, page, category, query, cancellation.get())
    }
    private fun listAt(source: ThirdSource, page: Int, category: String?, query: String?, token: Long): ThirdListPage {
        val params = mutableListOf("ac" to "list", "pg" to page.coerceIn(1, 10000).toString())
        if (!category.isNullOrBlank()) params += "t" to category
        if (!query.isNullOrBlank()) params += "wd" to query.take(100)
        return ThirdCms.parseList(get(ThirdCms.apiUrl(source.api, params), token))
    }
    fun detail(source: ThirdSource, id: String): ThirdDetail =
        ThirdCms.parseDetail(get(ThirdCms.apiUrl(source.api, listOf("ac" to "detail", "ids" to id)), cancellation.get()))
            ?: throw IOException("源站未返回影片详情")
    /** One explicitly requested page; at most three calls concurrently and 20 seconds overall. */
    fun searchAll(sources: List<ThirdSource>, query: String, page: Int = 1): ThirdAggregateResult {
        if (closed.get() || Thread.currentThread().isInterrupted) throw IOException("请求已取消")
        val token = cancellation.get()
        val selected = sources.distinctBy { it.api }.take(ThirdCms.MAX_SOURCES)
        if (selected.isEmpty()) return ThirdAggregateResult(emptyList(), 0, 0)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3) { task -> Thread(task, "hongguotv-source-search") }
        searches += pool
        try {
            val tasks = selected.map { source -> java.util.concurrent.Callable {
                val started = System.nanoTime()
                val response = runCatching { listAt(source, page.coerceIn(1, 50), null, query, token) }
                Triple(source, response, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(1))
            } }
            val results = pool.invokeAll(tasks, 20, TimeUnit.SECONDS)
            var successful = 0
            val next = mutableListOf<String>(); val failed = mutableListOf<String>()
            val measurements = linkedMapOf<String, SourceMeasurement>()
            val hits = results.flatMapIndexed { index, future ->
                val row = if (future.isCancelled) null else runCatching { future.get() }.getOrNull()
                val source = selected[index]
                val response = row?.second?.getOrNull()
                measurements[source.id] = SourceMeasurement(row?.third ?: 20_000, response != null)
                if (response == null) { failed += source.id; emptyList() }
                else {
                    successful++
                    if (response.hasMore && page < 50) next += source.id
                    response.videos.map { ThirdSearchHit(source, it) }
                }
            }
            if (closed.get() || cancellation.get() != token || Thread.currentThread().isInterrupted) throw IOException("请求已取消")
            return ThirdAggregateResult(ThirdCms.groupSearchHits(hits), successful, selected.size, page, next, failed, measurements)
        } finally {
            searches -= pool; pool.shutdownNow()
            // invokeAll cancels timed-out tasks; closing outstanding calls also interrupts body reads.
            synchronized(calls) { calls.toList().forEach(Call::cancel); calls.clear() }
        }
    }
}
