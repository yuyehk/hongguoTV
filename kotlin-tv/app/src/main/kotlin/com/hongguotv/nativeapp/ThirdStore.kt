// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ThirdFavorite(val sourceId: String, val video: ThirdVideo)
data class ThirdHistory(val sourceId: String, val video: ThirdVideo, val groupIndex: Int, val episodeIndex: Int, val episodeLabel: String, val updatedAt: Long)

/** Source credentials remain private here; never merged with official favorites or Library backup. */
class ThirdStore(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("third-sources-v1", Context.MODE_PRIVATE)
    private fun array(key: String) = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrElse { JSONArray() }
    fun sources(): List<ThirdSource> {
        val rows = array("sources")
        return (0 until minOf(rows.length(), ThirdCms.MAX_SOURCES)).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").take(128); val api = item.optString("api")
            if (id.isBlank() || !ThirdCms.validApi(api)) null else ThirdSource(id, item.optString("name").take(100), api)
        }.distinctBy { it.api }
    }
    fun mergeSources(incoming: List<ThirdSource>): Int {
        val old = sources()
        val next = (old + incoming.filter { ThirdCms.validApi(it.api) }).distinctBy { it.api }.take(ThirdCms.MAX_SOURCES)
        prefs.edit().putString("sources", JSONArray(next.map { JSONObject().put("id", it.id).put("name", it.name.take(100)).put("api", it.api) }).toString()).apply()
        return next.size - old.size
    }
    fun removeSource(id: String) {
        val next = sources().filterNot { it.id == id }
        prefs.edit().putString("sources", JSONArray(next.map { JSONObject().put("id", it.id).put("name", it.name).put("api", it.api) }).toString())
            .putString("favorites", favoritesJson(favorites().filterNot { it.sourceId == id }))
            .putString("history", historyJson(history().filterNot { it.sourceId == id })).apply()
        SourceProgressStore(context).removeSource(id)
    }
    fun currentId(): String? = runCatching { prefs.getString("current", null) }.getOrNull()
    fun setCurrent(id: String) { if (sources().any { it.id == id }) prefs.edit().putString("current", id).apply() }
    fun preferredId(): String? = prefs.getString("preferred", null)
    fun setPreferred(id: String?) { prefs.edit().apply { if (id == null) remove("preferred") else if (sources().any { it.id == id }) putString("preferred", id) }.apply() }
    fun measurements(): Map<String, SourceMeasurement> {
        val rows = array("measurements")
        return (0 until minOf(rows.length(), ThirdCms.MAX_SOURCES)).mapNotNull { i -> rows.optJSONObject(i)?.let {
            it.optString("id") to SourceMeasurement(it.optLong("ms").coerceIn(1, 60_000), it.optBoolean("ok"))
        } }.toMap()
    }
    fun recordMeasurements(incoming: Map<String, SourceMeasurement>) {
        val known = sources().map { it.id }.toSet()
        val next = (measurements() + incoming).filterKeys { it in known }
        prefs.edit().putString("measurements", JSONArray(next.map { (id, m) -> JSONObject().put("id", id).put("ms", m.elapsedMs).put("ok", m.success) }).toString()).apply()
    }
    fun orderedSources(): List<ThirdSource> {
        val rows = sources().associateBy { it.id }
        return SourcePreference.order(rows.keys.toList(), preferredId(), measurements()).mapNotNull(rows::get)
    }
    fun mergeLibrary(favorites: List<ThirdFavorite>, history: List<ThirdHistory>) {
        val known = sources().map { it.id }.toSet()
        val nextFavorites = (favorites() + favorites).filter { it.sourceId in known }.distinctBy { it.sourceId to it.video.id }.take(100)
        val nextHistory = (history() + history).filter { it.sourceId in known }.sortedByDescending { it.updatedAt }
            .distinctBy { it.sourceId to it.video.id }.take(100)
        prefs.edit().putString("favorites", favoritesJson(nextFavorites)).putString("history", historyJson(nextHistory)).apply()
    }
    fun favorites(): List<ThirdFavorite> {
        val rows = array("favorites")
        return (0 until minOf(rows.length(), 100)).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            val source = item.optString("source"); val id = item.optString("id").take(128)
            val name = item.optString("name").take(200)
            if (source.isBlank() || id.isBlank() || name.isBlank()) null
            else ThirdFavorite(source, ThirdVideo(id, name, item.optString("cover").takeIf(ThirdCms::validUrl).orEmpty(), item.optString("remarks").take(100)))
        }.distinctBy { it.sourceId to it.video.id }
    }
    fun isFavorite(source: ThirdSource, video: ThirdVideo) = favorites().any { it.sourceId == source.id && it.video.id == video.id }
    fun toggleFavorite(source: ThirdSource, video: ThirdVideo): Boolean {
        val existing = favorites()
        val isFavorite = existing.any { it.sourceId == source.id && it.video.id == video.id }
        val next = if (isFavorite) existing.filterNot { it.sourceId == source.id && it.video.id == video.id }
            else (listOf(ThirdFavorite(source.id, video)) + existing).take(100)
        prefs.edit().putString("favorites", favoritesJson(next)).apply(); return !isFavorite
    }
    fun history(): List<ThirdHistory> {
        val rows = array("history")
        return (0 until minOf(rows.length(), 100)).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            val source = item.optString("source"); val id = item.optString("id").take(128); val name = item.optString("name").take(200)
            if (source.isBlank() || id.isBlank() || name.isBlank()) null else ThirdHistory(source,
                ThirdVideo(id, name, item.optString("cover").takeIf(ThirdCms::validUrl).orEmpty()),
                item.optInt("group").coerceIn(0, 20), item.optInt("episode").coerceIn(0, 2000),
                item.optString("episodeLabel").take(100), item.optLong("at").coerceAtLeast(0))
        }.distinctBy { it.sourceId to it.video.id }
    }
    fun recordSelection(source: ThirdSource, detail: ThirdDetail, group: Int, episode: Int) {
        val selected = detail.groups.getOrNull(group)?.second?.getOrNull(episode) ?: return
        recordPlayback(source, detail.video, group, episode, selected.label)
    }
    fun recordPlayback(source: ThirdSource, video: ThirdVideo, group: Int, episode: Int, label: String) {
        val row = ThirdHistory(source.id, video, group, episode, label.take(100), System.currentTimeMillis())
        val next = (listOf(row) + history().filterNot { it.sourceId == source.id && it.video.id == video.id }).take(100)
        prefs.edit().putString("history", historyJson(next)).apply()
    }
    private fun historyJson(rows: List<ThirdHistory>) = JSONArray(rows.map { row ->
        JSONObject().put("source", row.sourceId).put("id", row.video.id).put("name", row.video.name).put("cover", row.video.cover)
            .put("group", row.groupIndex).put("episode", row.episodeIndex).put("episodeLabel", row.episodeLabel).put("at", row.updatedAt)
    }).toString()
    private fun favoritesJson(rows: List<ThirdFavorite>) = JSONArray(rows.map { row ->
        JSONObject().put("source", row.sourceId).put("id", row.video.id).put("name", row.video.name)
            .put("cover", row.video.cover).put("remarks", row.video.remarks)
    }).toString()
}
