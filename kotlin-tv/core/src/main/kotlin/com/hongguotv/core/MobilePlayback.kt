// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV 6672b55 and tsukiyo-zero/hongguoTV 0236976.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal data class MobileSignedRequest(val url: String, val headers: Map<String, String>)
internal fun interface MobilePlaybackSigner {
    fun sign(url: String, body: ByteArray, device: Map<String, String>): MobileSignedRequest
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
        val query = linkedMapOf("aid" to "8662", "app_name" to "novelread", "version_code" to "74332",
            "version_name" to "7.4.3.32", "channel" to "vivo_8662_64", "device_platform" to "android",
            "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74332",
            "iid" to device.getValue("iid"), "cdid" to device.getValue("cdid"),
            "device_id" to device.getValue("device_id"), "_rticket" to now.toString())
        val url = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1"
            .toHttpUrl().newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        // Level 3 can return exclusively app-only bytevc2; TV negotiates level 1.
        val json = JSONObject("""{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"player"}""")
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        val signed = signer.sign(url.toString(), bytes, device)
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)")
            .header("Accept", "application/json")
        signed.headers.filterKeys { !it.equals("Cookie", true) && !it.equals("Cookie2", true) }.forEach { (key, value) -> request.header(key, value) }
        // Stub and encoding must describe the exact compressed bytes passed to the signer.
        request.header("Content-Encoding", "gzip").header("x-ss-stub", digest("MD5", bytes).hex().uppercase())
            .post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        RequestScope.ensureActive()
        return RequestScope.execute(anonymousHttp(http).newCall(request.build())) { response ->
            if (!response.isSuccessful) throw PlaybackSourceException(PlaybackSourceException.Reason.HTTP_REJECTED, response.code)
            val text = boundedText(response, 2 * 1024 * 1024L)
            if (text.isBlank()) throw EmptyPlaybackResponseException()
            val root = try { JSONObject(text) } catch (_: org.json.JSONException) {
                throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            }
            PlaybackModels.checkStatus(root)
            val data = root.optJSONObject("data") ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            PlaybackModels.model(data, id) ?: throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
        }
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
