// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject
import org.json.JSONArray

/** Literal, bounded matching. User strings are never evaluated as regular expressions. */
object SocialPolicy {
    fun keywords(raw: String): List<String> = raw.take(4000).split('\n', ',', '，', ';', '；')
        .map { Normalizer.normalize(it, Normalizer.Form.NFKC).trim().take(40) }
        .filter { it.isNotBlank() && it.none(Char::isISOControl) }.distinct().take(50)
    fun blocked(text: String, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val normalized = Normalizer.normalize(text.take(8000), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return words.any { normalized.contains(it.lowercase(Locale.ROOT)) }
    }
    fun opacity(value: Int) = value.coerceIn(21, 100)
    fun alpha(value: Int) = (opacity(value) * 255 + 50) / 100
}

/** Separate from local library, device and account preferences. */
class SocialPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("social", Context.MODE_PRIVATE)
    private var cachedSeriesRaw: String? = null
    private var cachedSeries = JSONObject()
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var opacity: Int
        get() = SocialPolicy.opacity(prefs.getInt("opacity", 100))
        set(value) { prefs.edit().putInt("opacity", SocialPolicy.opacity(value)).apply() }
    var keywords: List<String>
        get() = SocialPolicy.keywords(prefs.getString("keywords", "").orEmpty())
        set(value) { prefs.edit().putString("keywords", SocialPolicy.keywords(value.joinToString("\n")).joinToString("\n")).apply() }
    private fun states(): JSONObject {
        val raw = prefs.getString("series-enabled", "{}").orEmpty()
        if (raw != cachedSeriesRaw) {
            cachedSeries = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            cachedSeriesRaw = raw
        }
        return cachedSeries
    }
    fun seriesEnabled(series: String?) = series?.let { states().optBoolean(it, true) } ?: true
    fun isEnabled(series: String?) = enabled && seriesEnabled(series)
    fun setSeriesEnabled(series: String, value: Boolean) {
        require(series.isNotBlank() && series.length <= 128 && series.none(Char::isISOControl))
        val rows = JSONObject(states().toString())
        rows.remove(series)
        rows.put(series, value)
        while (rows.length() > 500) rows.remove(rows.keys().next())
        prefs.edit().putString("series-enabled", rows.toString()).apply()
    }

    fun exportState(): JSONObject = JSONObject().put("enabled", enabled).put("opacity", opacity)
        .put("font", prefs.getInt("font", 24).takeIf { it in listOf(20, 24, 28, 32) } ?: 24)
        .put("lanes", prefs.getInt("lanes", 2).coerceIn(1, 4))
        .put("travel", prefs.getInt("travel", 12000).takeIf { it == 9000 || it == 12000 } ?: 12000)
        .put("keywords", JSONArray(keywords)).put("series", JSONObject(states().toString()))

    fun restoreState(json: JSONObject) {
        validateState(json)
        prefs.edit().putBoolean("enabled", json.getBoolean("enabled"))
            .putInt("opacity", json.getInt("opacity")).putInt("font", json.getInt("font"))
            .putInt("lanes", json.getInt("lanes")).putInt("travel", json.getInt("travel"))
            .putString("keywords", (0 until json.getJSONArray("keywords").length()).joinToString("\n") { json.getJSONArray("keywords").getString(it) })
            .putString("series-enabled", json.getJSONObject("series").toString()).apply()
    }
    companion object {
        fun validateState(json: JSONObject) {
            require(json.length() == 7 && json.get("enabled") is Boolean)
            fun number(key: String): Int {
                val value = json.get(key)
                require(value is Int || value is Long)
                val long = (value as Number).toLong()
                require(long in Int.MIN_VALUE..Int.MAX_VALUE)
                return long.toInt()
            }
            require(number("opacity") in 21..100 && number("font") in listOf(20, 24, 28, 32) &&
                number("lanes") in 1..4 && number("travel") in listOf(9000, 12000)) { "弹幕参数无效" }
            val words = json.getJSONArray("keywords")
            require(words.length() <= 50)
            val list = (0 until words.length()).map { require(words.get(it) is String); words.getString(it) }
            require(list.all { it.isNotBlank() && it.length <= 40 } && SocialPolicy.keywords(list.joinToString("\n")) == list) { "屏蔽词格式无效" }
            val rows = json.getJSONObject("series")
            require(rows.length() <= 500)
            rows.keys().asSequence().forEach { id ->
                require(id.isNotBlank() && id.length <= 128 && id.none(Char::isISOControl) && rows.get(id) is Boolean) { "单剧弹幕参数无效" }
            }
        }
    }
}
