package com.hongguotv.nativeapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Only included by the explicit complete-export action: source APIs may contain private tokens. */
class SourceLibraryBackup(context: Context) {
    private val store = ThirdStore(context)
    private val progress = SourceProgressStore(context)
    fun exportState(): String = JSONObject().put("version", 1)
        .put("sources", JSONArray(store.sources().map { JSONObject().put("id", it.id).put("name", it.name).put("api", it.api) }))
        .put("favorites", JSONArray(store.favorites().map { JSONObject().put("source", it.sourceId).put("id", it.video.id).put("name", it.video.name).put("cover", it.video.cover).put("remarks", it.video.remarks) }))
        .put("history", JSONArray(store.history().map { JSONObject().put("source", it.sourceId).put("id", it.video.id).put("name", it.video.name).put("cover", it.video.cover)
            .put("group", it.groupIndex).put("episode", it.episodeIndex).put("episodeLabel", it.episodeLabel).put("at", it.updatedAt) }))
        .put("progress", progress.progress()).put("series", progress.series()).toString()
    fun validate(raw: String) {
        Companion.validate(raw)
        val rows = JSONObject(raw).getJSONArray("sources").objects()
        val existing=store.sources()
        require(rows.none { incoming->existing.any {it.id==incoming.getString("id") && it.api!=incoming.getString("api")} }) { "备份片源标识与本机不同接口冲突，请先核对配置" }
        val incoming=rows.map {it.getString("api")}
        require((existing.map { it.api } + incoming).distinct().size <= ThirdCms.MAX_SOURCES) { "合并后片源超过 30 个，请先删除不再使用的片源" }
    }
    fun restoreState(raw: String) {
        validate(raw) // Validate every partition before the first write.
        val root = JSONObject(raw)
        val sources = root.getJSONArray("sources").objects().map { ThirdSource(it.getString("id"), it.getString("name"), it.getString("api")) }
        store.mergeSources(sources)
        val local = store.sources().associateBy { it.api }
        val aliases = sources.associate { it.id to (local[it.api]?.id ?: it.id) }
        fun video(it: JSONObject) = ThirdVideo(it.getString("id"), it.getString("name"), it.optString("cover"), it.optString("remarks"))
        store.mergeLibrary(root.getJSONArray("favorites").objects().map { ThirdFavorite(aliases.getValue(it.getString("source")), video(it)) },
            root.getJSONArray("history").objects().map { ThirdHistory(aliases.getValue(it.getString("source")), video(it), it.getInt("group"), it.getInt("episode"), it.getString("episodeLabel"), it.getLong("at")) })
        fun remap(rows: JSONArray, series: Boolean): JSONArray = JSONArray(rows.objects().map { original ->
            JSONObject(original.toString()).apply {
                val source = aliases.getValue(getString("source")); put("source", source)
                put("key", SourcePlaybackPolicy.progressKey(source, getString("video"), if (series) "" else getString("line"), if (series) "" else getString("episode")))
            }
        })
        progress.merge(remap(root.getJSONArray("progress"), false), remap(root.getJSONArray("series"), true))
    }
    companion object {
        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
        fun validate(raw: String) {
            require(raw.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "片源备份过大" }
            val root = JSONObject(raw)
            require(root.getInt("version") == 1)
            require(root.keys().asSequence().all { it in setOf("version", "sources", "favorites", "history", "progress", "series") })
            fun text(row: JSONObject, field: String, max: Int, blank: Boolean = false): String {
                val value = row.getString(field); require(value.length <= max && (blank || value.isNotBlank()) && value.none(Char::isISOControl)); return value
            }
            fun rows(field: String, cap: Int): List<JSONObject> { val a = root.getJSONArray(field); require(a.length() <= cap); return a.objects() }
            val sources = rows("sources", 30)
            val ids = sources.map { text(it, "id", 128) }.toSet()
            require(ids.size == sources.size)
            sources.forEach { text(it, "name", 100); require(ThirdCms.validApi(text(it, "api", 4096))) }
            require(sources.map { it.getString("api") }.distinct().size == sources.size)
            fun ref(row: JSONObject) { require(text(row, "source", 128) in ids) }
            fun entry(row: JSONObject) { ref(row); text(row, "id", 128); text(row, "name", 200); val cover = row.optString("cover"); require(cover.isBlank() || ThirdCms.validUrl(cover)) }
            rows("favorites", 100).forEach { entry(it); require(it.optString("remarks").length <= 100) }
            rows("history", 100).forEach { entry(it); require(it.getInt("group") in 0..20 && it.getInt("episode") in 0..2000 && it.getLong("at") >= 0); text(it, "episodeLabel", 100) }
            rows("progress", 500).forEach { ref(it); text(it, "video", 128); text(it, "line", 100); text(it, "episode", 100); require(it.getLong("position") in 0..7 * 86400_000L && it.getLong("at") >= 0)
                require(it.getString("key") == SourcePlaybackPolicy.progressKey(it.getString("source"), it.getString("video"), it.getString("line"), it.getString("episode"))) }
            rows("series", 100).forEach { ref(it); text(it, "video", 128); require(it.getLong("intro") in 0..300_000 && it.getLong("outro") in 0..300_000 && it.getLong("at") >= 0)
                require(it.getString("key") == SourcePlaybackPolicy.progressKey(it.getString("source"), it.getString("video"), "", "")) }
        }
    }
}
