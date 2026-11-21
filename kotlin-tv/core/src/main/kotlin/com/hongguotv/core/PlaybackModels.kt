// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV 6672b55 and tsukiyo-zero/hongguoTV 0236976.
package com.hongguotv.core

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

class PlaybackSourceException(val reason: Reason, val upstreamCode: Int? = null) : IOException(
    when (reason) {
        Reason.REJECTED -> "播放请求被拒绝（错误码 $upstreamCode）"
        Reason.HTTP_REJECTED -> "播放请求失败（HTTP $upstreamCode）"
        Reason.INVALID_RESPONSE -> "播放信息格式异常"
        Reason.NO_PLAYABLE_SOURCE -> "没有电视可播放的视频资源"
    }
) {
    enum class Reason { REJECTED, HTTP_REJECTED, INVALID_RESPONSE, NO_PLAYABLE_SOURCE }
}

/** Only a successful HTTP response with an empty body qualifies for one identity recovery. */
internal class EmptyPlaybackResponseException : IOException("播放接口返回空响应")

/** Pure bounded-response parsing: never select the first unrelated episode in a batch. */
internal object PlaybackModels {
    fun checkStatus(root: JSONObject) {
        val code = root.optInt("Code", root.optInt("code", 0))
        if (code != 0) throw PlaybackSourceException(PlaybackSourceException.Reason.REJECTED, code)
    }

    fun objectValue(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

    fun model(data: JSONObject, id: String): JSONObject? {
        val entries = data.optJSONObject("1004") ?: data.optJSONObject("video_model_data") ?: data
        // A direct video_model has no verifiable episode identity and is not accepted here.
        return objectValue(objectValue(entries.opt(id))?.opt("video_model"))
    }

    fun fallback(model: JSONObject): String? {
        val raw = model.opt("fallback_api")
        val value = when (raw) {
            is JSONArray -> raw.optString(0)
            else -> objectValue(raw)?.optString("fallback_api").orEmpty().ifEmpty { raw as? String ?: "" }
        }
        return safeUrl(value)?.takeIf { it.startsWith("https://") }
    }

    private fun safeUrl(raw: String): String? = cleanUrl(raw).trim().toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty()
    }?.toString()

    fun renditions(model: JSONObject): List<Rendition> {
        val list = model.opt("video_list")
        val entries: List<Pair<String, JSONObject>> = when (list) {
            is JSONObject -> list.keys().asSequence().mapNotNull { name -> objectValue(list.opt(name))?.let { name to it } }.toList()
            is JSONArray -> (0 until list.length()).mapNotNull { index -> objectValue(list.opt(index))?.let { "" to it } }
            else -> emptyList()
        }
        return entries.mapNotNull { (name, entry) ->
            // Malformed crypto or URL in one rendition must not hide other valid renditions.
            runCatching {
                val meta = entry.optJSONObject("video_meta")
                fun string(key: String) = entry.optString(key, "")
                val codec = string("codec_type").ifEmpty { meta?.optString("codec_type", "").orEmpty() }.lowercase()
                val codecFields = listOf(string("codec_type"), string("gear_des_key"), meta?.optString("codec_type", "").orEmpty()).joinToString(" ").lowercase()
                if (codecFields.contains("bytevc2") || Regex("(^|[^a-z0-9])bvc2([^a-z0-9]|$)").containsMatchIn(codecFields)) return@runCatching null
                if (codec in setOf("aac", "mp3", "opus", "vorbis", "flac", "ac3", "eac3") ||
                    codec.startsWith("mp4a") || codec.startsWith("audio/")) return@runCatching null
                val encryption = entry.optJSONObject("encrypt_info")
                // Inline player rows are video+CENC records, not the permissive legacy URL map.
                // Do not turn missing crypto metadata into a clear stream or accept audio-only rows.
                if (list is JSONArray && (meta == null || codec.isBlank() ||
                    meta.optInt("vwidth", meta.optInt("width", 0)) <= 0 ||
                    meta.optInt("vheight", meta.optInt("height", 0)) <= 0 || encryption == null)) return@runCatching null
                if (meta != null && encryption == null && string("spade_a").isBlank()) return@runCatching null
                val spade = encryption?.optString("spade_a", "").orEmpty().ifEmpty { string("spade_a") }
                val key = spade.takeIf(String::isNotBlank)?.let(MediaCrypto::deriveKey)
                if (encryption != null && key == null && encryption.opt("encrypt") !in listOf(false, 0, "0", "false")) return@runCatching null
                val resolved = listOf("main_url", "play_url", "backup_url_1").firstNotNullOfOrNull { field ->
                    runCatching {
                        val raw = cleanUrl(string(field)).trim()
                        safeUrl(raw) ?: if (raw.isBlank()) null else {
                            // Plain/base64 URLs do not depend on a possibly broken encrypted-URL seed.
                            val decoded = runCatching { safeUrl(String(decode64(raw), Charsets.UTF_8)) }.getOrNull()
                            decoded ?: model.optString("key_seed", "").takeIf(String::isNotBlank)?.let {
                                safeUrl(MediaCrypto.decryptUrl(raw, decode64(it)))
                            }
                        }
                    }.getOrNull()
                } ?: return@runCatching null
                val metaWidth = meta?.let { it.optInt("vwidth", it.optInt("width", 0)) } ?: 0
                val metaHeight = meta?.let { it.optInt("vheight", it.optInt("height", 0)) } ?: 0
                val width = entry.optInt("vwidth", entry.optInt("width", metaWidth))
                val height = entry.optInt("vheight", entry.optInt("height", metaHeight))
                val dimensions = if (width > 0 && height > 0) minOf(width, height).toString() else ""
                val qualityText = string("quality_desc").ifEmpty { meta?.optString("definition", "").orEmpty() }
                    .ifEmpty { dimensions.ifEmpty { string("height").ifEmpty { string("vheight").ifEmpty { string("quality").ifEmpty { name } } } } }
                Rendition(ContentRepository.qualityNumber(qualityText), string("codec_type").ifEmpty { meta?.optString("codec_type", "").orEmpty() }.lowercase(), width.takeIf { it > 0 }, height.takeIf { it > 0 }, resolved, key)
            }.getOrNull()
        }
    }
}
