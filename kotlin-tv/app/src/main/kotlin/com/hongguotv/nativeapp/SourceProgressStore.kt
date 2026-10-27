package com.hongguotv.nativeapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal class SourceProgressStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("source-portable-progress-v1", Context.MODE_PRIVATE)
    private fun rows(key: String) = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrElse { JSONArray() }
    fun progress(): JSONArray = rows("progress")
    fun series(): JSONArray = rows("series")
    fun position(key: String): Long = positionOrNull(key) ?: 0
    fun positionOrNull(key: String): Long? {
        val rows = progress()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .firstOrNull { it.optString("key") == key }?.optLong("position")?.coerceAtLeast(0)
    }
    fun removeSource(id: String) {
        fun retain(rows: JSONArray) = JSONArray((0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.filterNot { it.optString("source") == id }).toString()
        prefs.edit().putString("progress", retain(progress())).putString("series", retain(series())).apply()
    }
    fun save(list: SourcePlaylist, index: Int, position: Long) {
        val row = JSONObject().put("key", list.progressKey(index)).put("source", list.source.id).put("video", list.detail.video.id)
            .put("line", list.detail.groups[list.group].first).put("episode", list.episodes[index].label)
            .put("position", position.coerceIn(0, 7 * 86400_000L)).put("at", System.currentTimeMillis())
        merge(JSONArray().put(row), JSONArray())
    }
    fun skips(key: String): Pair<Long, Long> {
        val rows = series()
        val row = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.firstOrNull { it.optString("key") == key }
        return (row?.optLong("intro") ?: 0).coerceIn(0, 300_000) to (row?.optLong("outro") ?: 0).coerceIn(0, 300_000)
    }
    fun setSkips(list: SourcePlaylist, intro: Long, outro: Long) {
        val row = JSONObject().put("key", list.seriesKey).put("source", list.source.id).put("video", list.detail.video.id)
            .put("intro", intro.coerceIn(0, 300_000)).put("outro", outro.coerceIn(0, 300_000)).put("at", System.currentTimeMillis())
        merge(JSONArray(), JSONArray().put(row))
    }
    fun merge(progress: JSONArray, series: JSONArray) {
        fun mergeRows(old: JSONArray, incoming: JSONArray, cap: Int): String = JSONArray(
            (listOf(old, incoming).flatMap { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } })
                .sortedByDescending { it.optLong("at") }.distinctBy { it.optString("key") }.take(cap)).toString()
        prefs.edit().putString("progress", mergeRows(progress(), progress, 500)).putString("series", mergeRows(series(), series, 100)).apply()
    }
}
