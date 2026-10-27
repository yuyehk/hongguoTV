// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FeatureSkipMark(val introSeconds: Int = 0, val outroSeconds: Int = 0)

/** Small policy functions kept independent from Views and playback engines. */
object FeaturePolicy {
    val seekSteps = listOf(10, 15, 30, 60)
    val holdSpeeds = listOf(2f, 3f, 4f)
    fun seekStep(value: Int) = value.takeIf { it in seekSteps } ?: 10
    fun holdSpeed(value: Float) = value.takeIf { it in holdSpeeds } ?: 3f
    fun skipMark(intro: Int, outro: Int) = FeatureSkipMark(intro.coerceIn(0, 600), outro.coerceIn(0, 600))
    fun groupName(value: String) = value.replace(Regex("[\\s\\p{Cntrl}]+"), " ").trim().take(32)
}

/** One skip per segment, per playback start. A manual backward seek remains under user control. */
class FeatureSkipState {
    private var seriesId = ""
    private var introUsed = false
    private var outroUsed = false
    fun started(id: String) { seriesId = id; introUsed = false; outroUsed = false }
    fun target(id: String, positionMs: Long, durationMs: Long, mark: FeatureSkipMark): Long? {
        if (seriesId != id) started(id)
        val intro = mark.introSeconds.coerceIn(0, 600) * 1000L
        val outro = mark.outroSeconds.coerceIn(0, 600) * 1000L
        if (positionMs < 0 || durationMs <= 0 || intro + outro + 1000 >= durationMs) return null
        if (!introUsed && intro > 0 && positionMs < intro) { introUsed = true; return intro }
        if (!outroUsed && outro > 0 && positionMs in (durationMs - outro) until durationMs) {
            outroUsed = true; return durationMs
        }
        return null
    }
}

enum class FeatureTheme(val label: String) {
    DARK("深色"), BLACK("纯黑"), HIGH_CONTRAST("高对比"), NAVY("深海蓝"), OCEAN("海洋"),
    FOREST("森林"), PLUM("梅紫"), WINE("酒红"), COFFEE("咖啡"), SLATE("石板"),
    SUNSET("落日"), AMBER("琥珀"), TEAL("青碧"), INDIGO("靛蓝"), ROSE("玫瑰"),
    LIGHT("浅色"), PAPER("暖纸"), MINT("薄荷"), SKY("晴空"), LAVENDER("薰衣草")
}
enum class FeatureKeyAction(val label: String) {
    DEFAULT("跟随原有行为"), TOOLS("打开实用工具"), EPISODES("选集"), DANMAKU("弹幕开关"),
    SPEED("切换倍速"), FAVORITE("收藏 / 取消收藏"), SLEEP("睡眠定时")
}
data class FeatureStats(val playbackMs: Long, val starts: Long, val seriesCount: Int, val days: Map<String, Long>)

/**
 * Optional local functions, inspired by jycao2/hongguoTV. Kept separate from Library and account
 * storage: neither source URLs, tokens nor account/device identifiers can enter this export.
 */
class FeaturePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("feature-tools-v1", Context.MODE_PRIVATE)
    private var pendingPlaybackMs = 0L
    private var pendingDay: String? = null
    private fun string(key: String, default: String = "") = runCatching { prefs.getString(key, default) ?: default }.getOrDefault(default)
    private fun int(key: String, default: Int) = runCatching { prefs.getInt(key, default) }.getOrDefault(default)
    private fun long(key: String, default: Long = 0) = runCatching { prefs.getLong(key, default) }.getOrDefault(default).coerceAtLeast(0)
    private fun json(key: String) = runCatching { JSONObject(string(key, "{}")) }.getOrElse { JSONObject() }
    private fun validId(value: String) = value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl)

    var seekStepSeconds: Int
        get() = FeaturePolicy.seekStep(int("seekStep", 10))
        set(value) { prefs.edit().putInt("seekStep", FeaturePolicy.seekStep(value)).apply() }
    var holdSpeed: Float
        get() = FeaturePolicy.holdSpeed(runCatching { prefs.getFloat("holdSpeed", 3f) }.getOrDefault(3f))
        set(value) { prefs.edit().putFloat("holdSpeed", FeaturePolicy.holdSpeed(value)).apply() }
    var theme: FeatureTheme
        get() = FeatureTheme.entries.firstOrNull { it.name == string("theme") } ?: FeatureTheme.DARK
        set(value) { prefs.edit().putString("theme", value.name).apply() }
    var autoBackup: Boolean
        get() = runCatching { prefs.getBoolean("autoBackup", false) }.getOrDefault(false)
        set(value) { prefs.edit().putBoolean("autoBackup", value).apply() }
    var nextSeason: Boolean
        get() = runCatching { prefs.getBoolean("nextSeason", true) }.getOrDefault(true)
        set(value) { prefs.edit().putBoolean("nextSeason", value).apply() }
    var autoSkipIncompatible: Boolean
        get() = runCatching { prefs.getBoolean("autoSkipIncompatible", false) }.getOrDefault(false)
        set(value) { prefs.edit().putBoolean("autoSkipIncompatible", value).apply() }
    var backupIncludesExtras: Boolean
        get() = runCatching { prefs.getBoolean("backupIncludesExtras", false) }.getOrDefault(false)
        set(value) { prefs.edit().putBoolean("backupIncludesExtras", value).apply() }
    var lastBackupAt: Long
        get() = long("lastBackupAt")
        set(value) { prefs.edit().putLong("lastBackupAt", value.coerceAtLeast(0)).apply() }
    fun keyAction(keyCode: Int): FeatureKeyAction = FeatureKeyAction.entries.firstOrNull { it.name == string("key:$keyCode") } ?: FeatureKeyAction.DEFAULT
    fun setKeyAction(keyCode: Int, action: FeatureKeyAction) {
        if (keyCode in REMOTE_KEYS) prefs.edit().putString("key:$keyCode", action.name).apply()
    }
    fun skip(id: String): FeatureSkipMark {
        val row = json("skips").optJSONObject(id) ?: return FeatureSkipMark()
        return FeaturePolicy.skipMark(row.optInt("intro"), row.optInt("outro"))
    }
    fun setSkip(id: String, intro: Int, outro: Int) {
        require(validId(id))
        val rows = json("skips"); val mark = FeaturePolicy.skipMark(intro, outro)
        rows.remove(id)
        if (mark.introSeconds > 0 || mark.outroSeconds > 0) rows.put(id, JSONObject().put("intro", mark.introSeconds).put("outro", mark.outroSeconds))
        while (rows.length() > 500) rows.remove(rows.keys().next())
        prefs.edit().putString("skips", rows.toString()).apply()
    }
    fun groups(): List<String> = runCatching {
        val rows = JSONArray(string("groups", "[]"))
        (0 until minOf(rows.length(), 20)).map { FeaturePolicy.groupName(rows.optString(it)) }.filter(String::isNotEmpty).distinct()
    }.getOrDefault(emptyList())
    fun addGroup(name: String): Boolean {
        val clean = FeaturePolicy.groupName(name)
        val rows = groups()
        if (clean.isEmpty() || clean in rows || rows.size >= 20) return false
        prefs.edit().putString("groups", JSONArray(rows + clean).toString()).apply(); return true
    }
    fun removeGroup(name: String) {
        val assignments = json("assignments")
        assignments.keys().asSequence().toList().filter { assignments.optString(it) == name }.forEach(assignments::remove)
        prefs.edit().putString("groups", JSONArray(groups().filterNot { it == name }).toString())
            .putString("assignments", assignments.toString()).apply()
    }
    fun groupAssignments(): Map<String, String> {
        val rows = json("assignments"); val groups = groups().toSet()
        return rows.keys().asSequence().mapNotNull { id -> rows.optString(id).takeIf { it in groups }?.let { id to it } }.toMap()
    }
    fun groupOf(id: String): String? = groupAssignments()[id]
    fun assignGroup(id: String, name: String?) {
        require(validId(id)); require(name == null || name in groups())
        val assignments = json("assignments")
        if (name == null) assignments.remove(id) else assignments.put(id, name)
        while (assignments.length() > 1000) assignments.remove(assignments.keys().next())
        prefs.edit().putString("assignments", assignments.toString()).apply()
    }
    fun playbackStarted(id: String) {
        if (!validId(id)) return
        val ids = runCatching { prefs.getStringSet("viewedSeries", emptySet())?.toMutableSet() }.getOrNull() ?: mutableSetOf()
        if (ids.size < 1000) ids += id
        prefs.edit().putLong("starts", (long("starts") + 1).coerceAtMost(1_000_000_000))
            .putStringSet("viewedSeries", ids).apply()
    }
    /** Called only while playing; reject lifecycle gaps and persist at most once per 30 seconds. */
    fun recordPlayback(deltaMs: Long, now: Long = System.currentTimeMillis()) {
        if (deltaMs !in 1..60_000) return
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(now))
        if (pendingDay != null && pendingDay != day) flushStats()
        pendingDay = day; pendingPlaybackMs += deltaMs
        if (pendingPlaybackMs >= 30_000) flushStats()
    }
    fun flushStats() {
        if (pendingPlaybackMs <= 0) return
        val days = json("days"); val day = pendingDay ?: return
        days.put(day, (days.optLong(day).coerceAtLeast(0) + pendingPlaybackMs).coerceAtMost(MAX_STATS_MS))
        val retained = days.keys().asSequence().toList().sortedDescending().take(366).toSet()
        days.keys().asSequence().toList().filterNot { it in retained }.forEach(days::remove)
        prefs.edit().putLong("playbackMs", (long("playbackMs") + pendingPlaybackMs).coerceAtMost(MAX_STATS_MS))
            .putString("days", days.toString()).apply()
        pendingPlaybackMs = 0
    }
    fun stats(): FeatureStats {
        val days = json("days")
        val values = days.keys().asSequence().associateWith { days.optLong(it).coerceIn(0, MAX_STATS_MS) }.toMutableMap()
        pendingDay?.let { if (pendingPlaybackMs > 0) values[it] = (values[it] ?: 0) + pendingPlaybackMs }
        val count = runCatching { prefs.getStringSet("viewedSeries", emptySet())?.size ?: 0 }.getOrDefault(0)
        return FeatureStats(long("playbackMs") + pendingPlaybackMs, long("starts"), count, values)
    }
    fun resetStats() {
        pendingPlaybackMs = 0; pendingDay = null
        prefs.edit().remove("playbackMs").remove("starts").remove("viewedSeries").remove("days").apply()
    }
    /** Backup only explicit feature preferences. Never serialize SharedPreferences.all. */
    fun export(): String = JSONObject().put("version", 1).put("skips", json("skips"))
        .put("groups", JSONArray(groups())).put("assignments", json("assignments"))
        .put("seekStep", seekStepSeconds).put("holdSpeed", holdSpeed.toDouble()).put("theme", theme.name).put("nextSeason", nextSeason)
        .put("autoSkipIncompatible",autoSkipIncompatible)
        .put("keys", JSONObject().also { keys -> REMOTE_KEYS.forEach { keys.put(it.toString(), keyAction(it).name) } }).toString()
    /** Merge into local preferences; existing per-series choices win. Source/account state is absent. */
    fun restore(raw: String, restoreOptions:Boolean=false) {
        validate(raw)
        val incoming = JSONObject(raw)
        require(incoming.optInt("version") == 1) { "工具备份版本不支持" }
        val skips = incoming.optJSONObject("skips") ?: JSONObject()
        val groups = incoming.optJSONArray("groups") ?: JSONArray()
        val assignments = incoming.optJSONObject("assignments") ?: JSONObject()
        // Validate all partitions before changing any preference: malformed backups are atomic.
        require(skips.length() <= 500 && groups.length() <= 20 && assignments.length() <= 1000)
        val nextSkips = json("skips")
        nextSkips.keys().asSequence().toList().drop(500).forEach(nextSkips::remove)
        skips.keys().asSequence().toList().forEach { id ->
            if (validId(id) && !nextSkips.has(id) && nextSkips.length() < 500) skips.optJSONObject(id)?.let { row ->
                val mark = FeaturePolicy.skipMark(row.optInt("intro"), row.optInt("outro"))
                nextSkips.put(id, JSONObject().put("intro", mark.introSeconds).put("outro", mark.outroSeconds))
            }
        }
        val nextGroups = (groups() + (0 until groups.length()).map { FeaturePolicy.groupName(groups.optString(it)) })
            .filter(String::isNotEmpty).distinct().take(20)
        val nextAssignments = json("assignments")
        nextAssignments.keys().asSequence().toList().drop(1000).forEach(nextAssignments::remove)
        assignments.keys().asSequence().toList().forEach { id ->
            val name = FeaturePolicy.groupName(assignments.optString(id))
            if (validId(id) && nextAssignments.optString(id) !in nextGroups && name in nextGroups &&
                (nextAssignments.has(id) || nextAssignments.length() < 1000)) nextAssignments.put(id, name)
        }
        val edit=prefs.edit().putString("skips", nextSkips.toString()).putString("groups", JSONArray(nextGroups).toString())
            .putString("assignments", nextAssignments.toString())
        if(restoreOptions) {
            if(incoming.has("seekStep"))edit.putInt("seekStep",incoming.getInt("seekStep"))
            if(incoming.has("holdSpeed"))edit.putFloat("holdSpeed",incoming.getDouble("holdSpeed").toFloat())
            FeatureTheme.entries.firstOrNull {it.name==incoming.optString("theme")}?.let {edit.putString("theme",it.name)}
            if(incoming.has("nextSeason"))edit.putBoolean("nextSeason",incoming.getBoolean("nextSeason"))
            if(incoming.has("autoSkipIncompatible"))edit.putBoolean("autoSkipIncompatible",incoming.getBoolean("autoSkipIncompatible"))
            incoming.optJSONObject("keys")?.let { keys->REMOTE_KEYS.forEach {code->
                FeatureKeyAction.entries.firstOrNull {it.name==keys.optString(code.toString())}?.let {edit.putString("key:$code",it.name)}
            } }
        }
        edit.apply()
    }
    companion object {
        fun validate(raw:String) {
            require(raw.toByteArray(Charsets.UTF_8).size<=256*1024) {"工具备份过大"}
            val data=JSONObject(raw);require(data.getInt("version")==1)
            fun clean(value:Any,max:Int):Boolean=value is String && value.isNotBlank() && value.length<=max && value.none(Char::isISOControl)
            if(data.has("skips"))data.getJSONObject("skips").let { rows->
                require(rows.length()<=500)
                rows.keys().asSequence().forEach {id->
                    require(clean(id,128));val row=rows.getJSONObject(id)
                    require(row.keys().asSequence().all {it=="intro" || it=="outro"})
                    require(row.getInt("intro") in 0..600 && row.getInt("outro") in 0..600)
                }
            }
            if(data.has("groups"))data.getJSONArray("groups").let { rows->require(rows.length()<=20);repeat(rows.length()){require(clean(rows.get(it),32))} }
            if(data.has("assignments"))data.getJSONObject("assignments").let {rows->require(rows.length()<=1000);rows.keys().asSequence().forEach {require(clean(it,128) && clean(rows.get(it),32))} }
            if(data.has("seekStep"))require(data.getInt("seekStep") in FeaturePolicy.seekSteps)
            if(data.has("holdSpeed"))require(data.getDouble("holdSpeed").toFloat() in FeaturePolicy.holdSpeeds)
            if(data.has("theme"))require(data.get("theme") is String && data.getString("theme").length<=64)
            if(data.has("nextSeason"))require(data.get("nextSeason") is Boolean)
            if(data.has("autoSkipIncompatible"))require(data.get("autoSkipIncompatible") is Boolean)
            if(data.has("keys"))data.getJSONObject("keys").let {keys->
                require(keys.length()<=REMOTE_KEYS.size)
                keys.keys().asSequence().forEach { code->require(code.toIntOrNull() in REMOTE_KEYS && FeatureKeyAction.entries.any {it.name==keys.get(code)}) }
            }
        }
        // MENU and TV color keys only: never intercept Back, Home, volume or numeric navigation.
        val REMOTE_KEYS = listOf(82, 183, 184, 185, 186)
        private const val MAX_STATS_MS = 100L * 366 * 24 * 60 * 60 * 1000
    }
}
