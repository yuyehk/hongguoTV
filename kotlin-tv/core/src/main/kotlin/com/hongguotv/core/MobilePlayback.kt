// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV 6672b55 and tsukiyo-zero/hongguoTV 0236976.
// Bodyless video_tab recovery adapted from jackotom/hongguo-drama 92df757.
// Current App profile and VOD negotiation adapted from N3urda/hongguoTV ef328f3.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal data class MobileSignedRequest(val url: String, val headers: Map<String, String>)
internal fun interface MobilePlaybackSigner {
    fun sign(url: String, body: ByteArray?, device: Map<String, String>): MobileSignedRequest
}

internal object MobilePlayback {
    private val v5Signer by lazy { MobileV5Signer() }
    val signer = MobilePlaybackSigner { url, body, _ ->
        val parsed = url.toHttpUrl()
        val values = parsed.queryParameterNames.associateWith { parsed.queryParameter(it)!! }
        val signed = v5Signer.signPlayback(parsed.newBuilder().query(null).build().toString(), values, body, values.getValue("_rticket").toLong())
        MobileSignedRequest(signed.url, signed.headers)
    }

    /** Copy the transport, never modify a shared cookie jar or its account state. */
    fun anonymousHttp(http: OkHttpClient): OkHttpClient = http.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .addNetworkInterceptor { chain -> chain.proceed(chain.request().newBuilder().removeHeader("Cookie").removeHeader("Cookie2").build()) }
        .build()

    fun model(id: String, device: Map<String, String>, http: OkHttpClient,
              signer: MobilePlaybackSigner, now: Long): JSONObject {
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureActive()
        return try {
            compatibleModel(playerModel(id, device, http, signer, now), http)
        } catch (empty: EmptyPlaybackResponseException) {
            // Only both empty model endpoints qualify for the existing session recovery.
            RequestScope.ensureActive()
            val tab = videoTabModel(id, device, http, signer, now)
            if (PlaybackModels.renditions(tab).isNotEmpty()) tab else {
                // A correctly identified but incompatible tab model is not an expired identity.
                // Reuse it for one alternate App model request, without widening the host list.
                val alternate = try {
                    playerModel(id, device, http, signer, now, "api5-normal-sinfonlineb.fqnovel.com")
                } catch (alternateEmpty: EmptyPlaybackResponseException) {
                    throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE).apply {
                        addSuppressed(empty); addSuppressed(alternateEmpty)
                    }
                }
                compatibleModel(alternate, http)
            }
        }
    }

    private fun query(device: Map<String, String>) = linkedMapOf(
        "aid" to "8662", "app_name" to "novelread", "version_code" to "74532", "version_name" to "7.4.5.32",
        "update_version_code" to "74532", "manifest_version_code" to "74532", "os" to "android", "ssmix" to "a",
        "channel" to "update_64", "device_platform" to "android", "device_type" to "25053RT47C", "device_brand" to "Redmi",
        "os_version" to "16", "os_api" to "36", "ac" to "wifi", "language" to "zh", "host_abi" to "arm64-v8a",
        "iid" to device.getValue("iid"), "cdid" to device.getValue("cdid"), "device_id" to device.getValue("device_id"),
    )

    private fun videoTabModel(id: String, device: Map<String, String>, http: OkHttpClient,
                              signer: MobilePlaybackSigner, now: Long): JSONObject {
        val query = query(device) + linkedMapOf("video_id" to id, "video_id_type" to "0",
            "device_level" to "1", "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
            "from_video_id" to "", "_rticket" to now.toString())
        val url = "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/"
            .toHttpUrl().newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        val signed = signer.sign(url.toString(), null, device)
        val request = Request.Builder().url(signed.url).get()
            .header("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
            .header("Accept", "application/json; charset=utf-8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        // A bodyless player request carries only V5 signature headers, never POST metadata or cookies.
        signed.headers.filterKeys { it.lowercase() in setOf("x-khronos", "x-helios", "x-medusa") }
            .forEach { (key, value) -> request.header(key, value) }
        return readModel(request.build(), id, http)
    }

    private fun playerModel(id: String, device: Map<String, String>, http: OkHttpClient,
                            signer: MobilePlaybackSigner, now: Long, host: String = "reading.snssdk.com"): JSONObject {
        val query = query(device) + ("_rticket" to now.toString())
        val url = "https://$host/novel/player/multi_video_model/preload/v1"
            .toHttpUrl().newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        // The current App preload contract uses level 2; every returned codec is still validated.
        val json = JSONObject("""{"biz_param":{"detail_page_version":0,"device_level":2,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"preload"}""")
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        val signed = signer.sign(url.toString(), bytes, device)
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", System.getProperty("http.agent", "okhttp/4.12.0"))
            .header("Accept", "application/json")
        signed.headers.filterKeys { !it.equals("Cookie", true) && !it.equals("Cookie2", true) }.forEach { (key, value) -> request.header(key, value) }
        // Stub and encoding must describe the exact compressed bytes passed to the signer.
        request.header("Content-Encoding", "gzip").header("x-ss-stub", digest("MD5", bytes).hex().uppercase())
            .post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        RequestScope.ensureActive()
        return readModel(request.build(), id, http)
    }

    private fun readModel(request: Request, id: String, http: OkHttpClient): JSONObject {
        RequestScope.ensureActive()
        return RequestScope.execute(anonymousHttp(http).newCall(request)) { response ->
            if (!response.isSuccessful) throw PlaybackSourceException(PlaybackSourceException.Reason.HTTP_REJECTED, response.code)
            val text = boundedText(response, 2 * 1024 * 1024L)
            if (text.isBlank()) {
                if (!response.header("Retry-After").isNullOrBlank()) throw IOException("播放服务繁忙，请稍后重试")
                throw EmptyPlaybackResponseException()
            }
            val root = try { JSONObject(text) } catch (_: org.json.JSONException) {
                throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            }
            PlaybackModels.checkStatus(root)
            val data = root.optJSONObject("data") ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            PlaybackModels.model(data, id) ?: throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
        }
    }

    /** Negotiate only on the exact model-provided VOD URL, without forwarding model/account headers. */
    internal fun compatibleModel(model: JSONObject, http: OkHttpClient): JSONObject {
        if (PlaybackModels.renditions(model).isNotEmpty()) return model
        // URL-only legacy models retain ContentRepository's original authorized-URL recovery.
        if (!model.has("video_list")) return model
        val endpoint = PlaybackModels.fallback(model) ?: return model
        val transport = anonymousHttp(http)
        var last: JSONObject? = null
        for (codec in listOf("1", "3")) {
            RequestScope.ensureActive()
            val url = endpoint.toHttpUrl().newBuilder().setQueryParameter("codec_type", codec).build()
            val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
            val candidate = RequestScope.execute(transport.newCall(request)) { response ->
                if (!response.isSuccessful) throw PlaybackSourceException(PlaybackSourceException.Reason.HTTP_REJECTED, response.code)
                val root = try { JSONObject(boundedText(response, 2 * 1024 * 1024L)) } catch (_: org.json.JSONException) {
                    throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
                }
                PlaybackModels.checkStatus(root)
                root.optJSONObject("video_info")?.optJSONObject("data")
                    ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            }
            if (PlaybackModels.renditions(candidate).isNotEmpty()) return candidate
            last = candidate
        }
        // Preserve the existing bounded auto-skip behavior for an explicitly unsupported video codec.
        val rows = last?.optJSONObject("video_list")
        val seed = last?.optString("key_seed").orEmpty()
        if (rows != null && rows.length() > 0 && rows.keys().asSequence().all { key ->
            val row = rows.optJSONObject(key)
            row != null && row.optString("codec_type").lowercase() in setOf("bytevc2", "bvc2") && runCatching {
                val raw = cleanUrl(row.optString("main_url"))
                val url = (if (seed.isBlank()) raw else MediaCrypto.decryptUrl(raw, decode64(seed))).toHttpUrlOrNull()
                url != null && url.username.isEmpty() && url.password.isEmpty()
            }.getOrDefault(false)
        }) throw IncompatibleStreamException()
        throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
    }

    fun ladder(id: String, device: Map<String, String>, http: OkHttpClient,
               signer: MobilePlaybackSigner, now: Long): StreamLadder = ladder(model(id, device, http, signer, now))

    fun ladder(model: JSONObject): StreamLadder {
        val rows = PlaybackModels.renditions(model)
        if (rows.isEmpty()) throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
        return StreamLadder(rows)
    }

    internal fun boundedText(response: Response, limit: Long): String {
        val body = response.body ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
        if (body.contentLength() > limit) throw IOException("播放信息过大")
        val source = body.source()
        source.request(limit + 1)
        RequestScope.ensureActive()
        if (source.buffer.size > limit) throw IOException("播放信息过大")
        return source.readUtf8()
    }
}
