package com.hongguotv.nativeapp

import org.json.JSONArray
import org.json.JSONObject

/** Private, single-use handoff. Only the selected line is retained, with a fixed episode bound. */
internal data class SourcePlaylist(val source: ThirdSource, val detail: ThirdDetail, val group: Int, val index: Int, val originalGroup: Int = group, val episodeOffset: Int = 0) {
    val episodes get() = detail.groups[group].second
    fun title(at: Int) = "${detail.video.name.take(120)} · ${episodes[at].label.take(76)}"
    fun progressKey(at: Int) = SourcePlaybackPolicy.progressKey(source.id, detail.video.id, detail.groups[group].first, episodes[at].label)
    val seriesKey get() = SourcePlaybackPolicy.progressKey(source.id, detail.video.id, "", "")
}

internal object SourcePlaybackQueue {
    private var pending: Pair<Long, SourcePlaylist>? = null
    @Synchronized fun offer(source: ThirdSource, detail: ThirdDetail, group: Int, index: Int) {
        val line = detail.groups.getOrNull(group) ?: return
        if (index !in line.second.indices || index >= 2000 || !SourceMediaPolicy.valid(line.second[index].url)) return
        var radius = 250
        var bounded: SourcePlaylist
        do {
            val first = (index - radius).coerceAtLeast(0)
            val episodes = line.second.subList(first, (index + radius + 1).coerceAtMost(minOf(line.second.size, 2000)))
            if (episodes.any { !SourceMediaPolicy.valid(it.url) }) return
            bounded = SourcePlaylist(source, detail.copy(groups = listOf(line.first to episodes.toList())), 0, index-first, group, first)
            radius /= 2
        } while (encode(bounded).toByteArray(Charsets.UTF_8).size > 196608 && radius > 0)
        pending = System.nanoTime() to bounded
    }
    @Synchronized fun claim(url: String, title: String): SourcePlaylist? {
        val entry = pending; pending = null
        return entry?.second?.takeIf { System.nanoTime() - entry.first < 60_000_000_000L && it.episodes[it.index].url == url &&
            (it.title(it.index) == title || "${it.detail.video.name} · ${it.episodes[it.index].label}".take(200) == title.take(200)) }
    }
    fun encode(list: SourcePlaylist): String = JSONObject().put("source", list.source.id).put("api", list.source.api).put("sourceName", list.source.name)
        .put("video", list.detail.video.id).put("name", list.detail.video.name).put("line", list.detail.groups[list.group].first)
        .put("group", list.originalGroup).put("index", list.index).put("offset", list.episodeOffset)
        .put("episodes", JSONArray(list.episodes.map { JSONObject().put("label", it.label).put("url", it.url) })).toString()
    fun decode(raw: String): SourcePlaylist? = runCatching {
        require(raw.toByteArray(Charsets.UTF_8).size <= 196608)
        val row = JSONObject(raw); val episodes = row.getJSONArray("episodes")
        require(episodes.length() in 1..2000)
        val parsed = (0 until episodes.length()).map { index -> episodes.getJSONObject(index).let {
            val url = it.getString("url"); require(SourceMediaPolicy.valid(url)); ThirdEpisode(it.getString("label").take(100), url)
        } }
        val source = ThirdSource(row.getString("source"), row.getString("sourceName"), row.getString("api"))
        require(ThirdCms.validApi(source.api)); val index = row.getInt("index"); require(index in parsed.indices)
        SourcePlaylist(source, ThirdDetail(ThirdVideo(row.getString("video"), row.getString("name")), "", listOf(row.getString("line") to parsed)), 0, index, row.getInt("group"), row.optInt("offset"))
    }.getOrNull()
}
