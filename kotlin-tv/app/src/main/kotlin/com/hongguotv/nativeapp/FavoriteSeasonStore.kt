// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.Series
import org.json.JSONArray
import org.json.JSONObject

data class FavoriteSeasonNotice(val current: Series,val next: Series) {
    // First observation establishes availability; it cannot establish a release date.
    val label: String get()="可看下一季"
}

/** Local availability notices, isolated from favorites, accounts and backup credentials. */
class FavoriteSeasonStore(context: Context) {
    private val prefs=context.applicationContext.getSharedPreferences("native-favorite-seasons-v1",Context.MODE_PRIVATE)
    private data class Entry(val notice: FavoriteSeasonNotice,val acknowledged: Boolean)
    private companion object {
        const val MAX_ENTRIES=500
        val lock=Any()
        val validId=Regex("[0-9]{1,30}")
        fun valid(series: Series)=validId.matches(series.id) && series.title.isNotBlank()
    }

    private fun read(): LinkedHashMap<String,Entry> {
        val rows=runCatching { JSONArray(prefs.getString("entries","[]")) }.getOrNull() ?: return linkedMapOf()
        val entries=linkedMapOf<String,Entry>()
        for(index in 0 until rows.length()) {
            val row=rows.optJSONObject(index) ?: continue
            val entry=runCatching {
                val current=Series.fromJson(row.getJSONObject("current"))
                val next=Series.fromJson(row.getJSONObject("next"))
                require(valid(current) && valid(next) && current.id!=next.id)
                Entry(FavoriteSeasonNotice(current,next),row.optBoolean("acknowledged",false))
            }.getOrNull() ?: continue
            entries.remove(entry.notice.current.id)
            entries[entry.notice.current.id]=entry
            if(entries.size>MAX_ENTRIES) entries.remove(entries.keys.first())
        }
        return entries
    }

    private fun save(entries: Map<String,Entry>) {
        val rows=JSONArray()
        entries.values.forEach { entry ->
            rows.put(JSONObject().put("current",entry.notice.current.toJson()).put("next",entry.notice.next.toJson())
                .put("acknowledged",entry.acknowledged))
        }
        prefs.edit().putString("entries",rows.toString()).apply()
    }

    fun observe(current: Series,next: Series) = synchronized(lock) {
        if(!valid(current) || !valid(next) || current.id==next.id) return@synchronized
        val entries=read()
        val previous=entries.remove(current.id)
        entries[current.id]=Entry(FavoriteSeasonNotice(current,next),previous?.notice?.next?.id==next.id && previous?.acknowledged==true)
        while(entries.size>MAX_ENTRIES) entries.remove(entries.keys.first())
        save(entries)
    }

    fun pending(): List<FavoriteSeasonNotice> = synchronized(lock) {
        read().values.filterNot { it.acknowledged }.map { it.notice }.reversed()
    }

    fun acknowledge(currentId: String) = synchronized(lock) {
        val entries=read()
        val entry=entries[currentId] ?: return@synchronized
        if(!entry.acknowledged) { entries[currentId]=entry.copy(acknowledged=true);save(entries) }
    }

    fun prune(validIds: Set<String>) = synchronized(lock) {
        val entries=read()
        if(entries.keys.retainAll(validIds)) save(entries)
    }
}
