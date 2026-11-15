// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.UUID
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class PlaybackRegistrationDeferredException : IOException("播放设备注册暂不可用，请约一分钟后重试")

/** Resolve and briefly retain only the models of episodes requested by this playback session. */
internal class PlaybackModelBatch(private val clock: () -> Long) {
    private data class CachedModel(val model: JSONObject, val fetchedAt: Long)
    private data class Profile(val values: Map<String, String>, val cookie: String)

    private val models = LinkedHashMap<String, CachedModel>()
    @Volatile private var preferUntil = 0L
    val preferred get() = clock() < preferUntil
    @Volatile private var registrationRetryAt = 0L
    private var profile: Profile? = null
    private var refreshAllowedAt = 0L

    @Synchronized
    fun get(id: String): JSONObject? {
        val cached = models[id] ?: return null
        if (clock() - cached.fetchedAt in 0 until CACHE_MS) return cached.model
        models.remove(id)
        return null
    }

    @Synchronized
    fun invalidate(id: String) {
        models.remove(id)
    }

    @Synchronized
    private fun remember(ids: List<String>, data: JSONObject) {
        val now = clock()
        models.entries.removeAll { now - it.value.fetchedAt !in 0 until CACHE_MS }
        for (id in ids) {
            val model = data.optJSONObject(id)?.let(::parsePlaybackModel) ?: continue
            models.remove(id)
            models[id] = CachedModel(model, now)
            if (models.size > CACHE_LIMIT) models.remove(models.keys.first())
        }
        preferUntil = now + CACHE_MS
    }

    fun fetch(
        id: String,
        episodes: List<String>,
        http: OkHttpClient,
        allowSessionRefresh: Boolean,
        resolve: (Signer.Request, OkHttpClient) -> String,
    ): JSONObject {
        val ids = listOf(id) + episodes.drop(episodes.indexOf(id).let { if (it < 0) episodes.size else it + 1 })
            .asSequence().filter { it != id && it.matches(EPISODE_ID) }.distinct().take(BATCH_LIMIT - 1).toList()
        val client = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).callTimeout(8, TimeUnit.SECONDS).build()
        val registrationClient = client.newBuilder().callTimeout(16, TimeUnit.SECONDS)
            .readTimeout(16, TimeUnit.SECONDS).build()
        val device = register(registrationClient)
        fetchModels(id, ids, client, device, resolve)?.let { return it }
        if (allowSessionRefresh) {
            val renewed = refresh(registrationClient, device)
            if (renewed != null) fetchModels(id, ids, client, renewed, resolve)?.let { return it }
        }
        throw EmptyContentResponse()
    }

    /** A null result means every playback host returned an empty body. */
    private fun fetchModels(
        id: String,
        ids: List<String>,
        client: OkHttpClient,
        device: Profile,
        resolve: (Signer.Request, OkHttpClient) -> String,
    ): JSONObject? {
        var allEmpty = true
        val signer = PlaybackSigner(clock)
        val parameters = device.values + linkedMapOf(
            "video_id" to id, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
            "from_video_id" to "",
        )
        for (host in TAB_HOSTS) {
            val signed = signer.sign("https://$host/reading/bookapi/video_tab/multi_video_model/v1/", parameters)
            val root = try {
                val body = resolve(withCookies(signed, device), client)
                if (body.isBlank()) throw EmptyContentResponse()
                allEmpty = false
                JSONObject(body)
            } catch (error: JSONException) {
                continue
            } catch (error: IOException) {
                if (RequestScope.isCurrentCancelled() || !canTryOtherHost(error)) throw error
                if (error !is EmptyContentResponse) allEmpty = false
                continue
            }
            val code = root.optInt("code", root.optInt("Code", -1))
            if (code != 0) throw IOException("播放接口暂不可用（错误码 $code）")
            val data = root.optJSONObject("data") ?: throw IOException("播放信息为空")
            val rows = data.optJSONObject("video_model_data") ?: data
            val model = rows.optJSONObject(id)?.let(::parsePlaybackModel)
                ?: throw IOException("播放接口未返回当前分集")
            remember(listOf(id), rows)
            return model
        }
        val payload = JSONObject()
            .put("biz_param", JSONObject(BIZ_PARAMETERS))
            .put("mixed_video_id_map", JSONObject().put("1004", JSONArray(ids)))
            .toString().toByteArray()
        var failure: IOException = EmptyContentResponse()
        for (host in HOSTS) {
            val signed = signer.sign("https://$host/novel/player/multi_video_model/v1/", device.values, payload)
            val root = try {
                val body = resolve(withCookies(signed, device), client)
                if (body.isBlank()) throw EmptyContentResponse()
                allEmpty = false
                JSONObject(body)
            } catch (error: JSONException) {
                failure = IOException("批量播放接口返回格式异常", error)
                continue
            } catch (error: IOException) {
                if (RequestScope.isCurrentCancelled() || !canTryOtherHost(error)) throw error
                if (error !is EmptyContentResponse) allEmpty = false
                failure = error
                continue
            }
            val code = root.optInt("Code", root.optInt("code", -1))
            if (code != 0) throw IOException("批量播放接口暂不可用（错误码 $code）")
            val data = root.optJSONObject("data") ?: throw IOException("播放信息为空")
            val model = data.optJSONObject(id)?.let(::parsePlaybackModel)
                ?: throw IOException("播放接口未返回当前分集")
            remember(ids, data)
            return model
        }
        if (allEmpty) return null
        throw failure
    }

    private fun withCookies(request: Signer.Request, device: Profile): Signer.Request =
        if (device.cookie.isEmpty()) request
        else request.copy(headers = request.headers + ("Cookie" to device.cookie))

    @Synchronized
    private fun register(client: OkHttpClient): Profile = profile ?: registerFresh(client)

    @Synchronized
    private fun refresh(client: OkHttpClient, failed: Profile): Profile? {
        if (RequestScope.isCurrentCancelled()) return null
        // Another foreground request may already have renewed the shared session.
        profile?.takeIf { it !== failed }?.let { return it }
        val now = clock()
        if (now < refreshAllowedAt) return null
        refreshAllowedAt = now + REGISTRATION_BACKOFF_MS
        return registerFresh(client)
    }

    private fun registerFresh(client: OkHttpClient): Profile {
        val now = clock()
        val waitMs = registrationRetryAt - now
        if (waitMs > SHORT_BACKOFF_MS) throw PlaybackRegistrationDeferredException()
        if (waitMs > 0) throw IOException("播放设备注册暂不可用，请稍后再试")
        val values = VendorConstants.device + ("cdid" to UUID.randomUUID().toString())
        val payload = DeviceRegister.payload(values, now)
        val request = Request.Builder().url(DeviceRegister.url()).apply {
            DeviceRegister.headers(payload, values, now).forEach { (name, value) -> header(name, value) }
            post(payload.toRequestBody("application/octet-stream".toMediaType()))
        }.build()
        val registered = try {
            RequestScope.execute(client.newCall(request)) { response ->
                val body = readContentText(response)
                if (body.isBlank()) throw EmptyContentResponse("播放设备注册返回空响应，请稍后再试")
                DeviceRegister.parse(body, response.headers("Set-Cookie"))
            }
        } catch (error: IOException) {
            if (RequestScope.isCurrentCancelled()) throw error
            if (error is ContentHttpException && error.statusCode == 429) deferRegistration()
            registrationRetryAt = clock() + SHORT_BACKOFF_MS
            throw error
        }
        if (registered == null || !registered.deviceId.matches(DEVICE_ID) || !registered.installId.matches(DEVICE_ID)) {
            deferRegistration()
        }
        val playback = values + mapOf(
            "device_id" to registered.deviceId, "iid" to registered.installId,
            "version_code" to "73532", "version_name" to "7.3.5.32",
            "manifest_version_code" to "73532", "update_version_code" to "73532",
            "pv_player" to "73532",
        )
        val cookie = registered.cookies.filterKeys { it in REGISTRATION_COOKIES }
            .entries.joinToString("; ") { (key, value) -> "$key=$value" }
        return Profile(playback, cookie).also { profile = it }
    }

    private fun deferRegistration(): Nothing {
        registrationRetryAt = clock() + REGISTRATION_BACKOFF_MS
        throw PlaybackRegistrationDeferredException()
    }

    private fun canTryOtherHost(error: IOException): Boolean = when (error) {
        is EmptyContentResponse, is InterruptedIOException, is SocketException, is UnknownHostException -> true
        is ContentHttpException -> error.statusCode in TEMPORARY_HTTP_FAILURES
        else -> false
    }

    private companion object {
        const val BATCH_LIMIT = 8
        const val CACHE_LIMIT = 24
        const val CACHE_MS = 180_000L
        const val SHORT_BACKOFF_MS = 3_000L
        const val REGISTRATION_BACKOFF_MS = 60_000L
        val TEMPORARY_HTTP_FAILURES = setOf(500, 502, 503, 504)
        val EPISODE_ID = Regex("[0-9]{1,30}")
        val DEVICE_ID = Regex("[1-9][0-9]{0,29}")
        val HOSTS = listOf("api5-normal-sinfonlineb.fqnovel.com", "api5-normal-hl.fqnovel.com")
        val TAB_HOSTS = listOf("api5-normal-sinfonlineb.fqnovel.com", "reading.snssdk.com")
        val REGISTRATION_COOKIES = setOf("install_id", "ttreq", "store-region", "store-region-src", "ttwid", "msToken")
        const val BIZ_PARAMETERS =
            """{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024}"""
    }
}

internal fun parsePlaybackModel(entry: JSONObject): JSONObject? = when (val model = entry.opt("video_model")) {
    is JSONObject -> model
    is String -> try { JSONObject(model) } catch (_: org.json.JSONException) { null }
    else -> null
}
