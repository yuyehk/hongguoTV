// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.LibraryBackup
import org.json.JSONObject

/** Portable files contain only explicitly selected library/tool data, never arbitrary preferences. */
object PortableBackupCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun encode(library:String,tools:String,savedAt:Long,sources:String?=null,appearance:String?=null):String {
        val json=JSONObject().put("format","hongguotv.local-tools").put("version",if(sources!=null || appearance!=null)2 else 1)
            .put("library",library).put("tools",tools).put("savedAt",savedAt)
        sources?.let {json.put("sources",it)};appearance?.let {json.put("appearance",it)}
        return sanitize(json.toString())
    }
    fun sanitize(raw: String): String {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份文件过大" }
        val root = JSONObject(raw)
        val version=root.getInt("version")
        require(root.getString("format") == "hongguotv.local-tools" && version in 1..2) { "备份格式不支持" }
        val incomingLibrary = root.getString("library")
        val createdAt = JSONObject(incomingLibrary).optLong("createdAt", 0).coerceAtLeast(0)
        val library = JSONObject(LibraryBackup.encode(LibraryBackup.decode(incomingLibrary)))
            .put("createdAt", createdAt).toString()
        val tools = JSONObject(root.getString("tools"))
        FeaturePreferences.validate(tools.toString())
        require(tools.toString().toByteArray(Charsets.UTF_8).size <= 256 * 1024 && tools.optInt("version") == 1)
        val safeTools = JSONObject()
        listOf("version", "skips", "groups", "assignments", "seekStep", "holdSpeed", "theme", "keys", "nextSeason","autoSkipIncompatible").forEach { key ->
            if (tools.has(key)) safeTools.put(key, tools.get(key))
        }
        val result=JSONObject().put("format", "hongguotv.local-tools").put("version", version)
            .put("savedAt", root.getLong("savedAt").coerceAtLeast(0))
            .put("library", library).put("tools", safeTools.toString())
        if(version==2) {
            if(root.has("sources"))root.getString("sources").let {SourceLibraryBackup.validate(it);result.put("sources",it)}
            if(root.has("appearance"))root.getString("appearance").let {AppearanceController.validateState(it);result.put("appearance",it)}
        }
        return result.toString().also {require(it.toByteArray(Charsets.UTF_8).size<=MAX_BYTES)}
    }

    fun decode(raw: String): LocalFeatureBackup {
        val safe = JSONObject(sanitize(raw))
        return LocalFeatureBackup(LibraryBackup.decode(safe.getString("library")), safe.getString("tools"), safe.getLong("savedAt"),safe.optString("sources").takeIf {it.isNotEmpty()},safe.optString("appearance").takeIf {it.isNotEmpty()})
    }
}
