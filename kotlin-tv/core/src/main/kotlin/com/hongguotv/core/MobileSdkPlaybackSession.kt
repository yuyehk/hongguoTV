// SPDX-License-Identifier: GPL-3.0-only
// Anonymous SDK session adapted from jackotom/hongguo-drama ea5a852 (sdk-playback).
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** Repository-owned anonymous identity and token. No account store, disk state, or recovery loop. */
internal class MobileSdkPlaybackSession(
    http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val factory: MobileSdkRequestFactory = MobileSdkSigner(clock),
    private val decodeExchange: (ByteArray) -> MobileSdkExchangeResult = {
        MobileSdkExchange.response(it, MobileSdkMaterials.exchangeKey, MobileSdkMaterials.exchangeInnerKey)
    },
) {
    private class WireContext(val cookie: String?)
    private class Identity(val device: Map<String, String>, val cookies: Map<String, String>)
    private val http = http.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cache(null).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            // Even a caller's account interceptor cannot add credentials to this private transport.
            val original = chain.request()
            val cookie = original.tag(WireContext::class.java)?.cookie
            val clean = original.newBuilder()
            original.headers.names().filter(::accountHeader).forEach(clean::removeHeader)
            clean.removeHeader("Cookie").removeHeader("Cookie2")
            if (cookie != null) clean.header("Cookie", cookie)
            chain.proceed(clean.build())
        }.build()
    private val lock = Object()
    private var busy = false
    // Only the owner of busy accesses these fields; publication is through lock.
    private var identity: Identity? = null
    private var token: String? = null
    private var ladon = 0L
    private var initializationFailedAt: Long? = null

    fun model(id: String, allowSessionRecovery: Boolean = true): JSONObject {
        require(MobileSdkSigner.acceptsEpisodeId(id))
        enter()
        // A speculative preload may use a ready session, never initialize/restore one.
        // Compute while owning busy; this local refusal must not alter recovery state.
        val readyOrRecoveryAllowed = token != null || allowSessionRecovery
        try {
            RequestScope.ensureActive()
            if (!readyOrRecoveryAllowed) throw IOException("播放会话尚未就绪")
            if (token == null) initialize()
            val current = identity ?: invalid()
            val prepared = factory.playback(id, current.device, current.cookies, token!!, ladon)
            val request = wireRequest(prepared, PLAYER_HOST, PLAYER_PATH, current)
            RequestScope.ensureActive()
            // Advance once before sending: a cancelled response must not replay the previous state.
            ladon = prepared.nextLadon
            val bytes = execute(request, MODEL_LIMIT)
            val root = try { JSONObject(bytes.toString(Charsets.UTF_8)) } catch (_: org.json.JSONException) { invalid() }
            PlaybackModels.checkStatus(root)
            val data = root.optJSONObject("data") ?: invalid()
            return PlaybackModels.model(data, id)
                ?: throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
        } catch (failure: Exception) {
            if (!readyOrRecoveryAllowed) throw failure
            // A failed/ambiguous send cannot reuse a token with uncertain rolling state.
            // Keep the anonymous device, and only a later call may initialize again.
            token = null
            ladon = 0L
            RequestScope.ensureActive()
            if (failure !is java.util.concurrent.CancellationException && initializationFailedAt == null) initializationFailedAt = clock()
            throw failure
        } finally {
            synchronized(lock) { busy = false; lock.notifyAll() }
        }
    }

    private fun enter() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180)
        synchronized(lock) {
            while (busy) {
                RequestScope.ensureActive()
                if (System.nanoTime() >= deadline) throw IOException("播放会话等待超时")
                try { lock.wait(100) } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("请求已取消", interrupted)
                }
            }
            RequestScope.ensureActive()
            busy = true
        }
    }

    private fun initialize() {
        initializationFailedAt?.let { if (clock() - it < 30_000) throw IOException("播放会话暂不可用，请稍后重试") }
        initializationFailedAt = null
        try {
            val current = identity ?: register().also { identity = it }
            var nextToken: String? = null
            var nextLadon = 0L
            repeat(2) { step ->
                RequestScope.ensureActive()
                val prepared = factory.bootstrap(current.device, current.cookies, nextToken, nextLadon)
                val request = wireRequest(prepared, SDK_HOST, SDK_PATH, current)
                val result = decodeExchange(execute(request, EXCHANGE_LIMIT))
                RequestScope.ensureActive()
                if (result.token.length !in 1..256 || result.token.any { it.code !in 33..126 }) invalid()
                nextLadon = prepared.nextLadon
                if (result.status == 200) {
                    token = result.token
                    ladon = nextLadon
                    initializationFailedAt = null
                    return
                }
                if (result.status != 202 || step == 1) {
                    throw PlaybackSourceException(PlaybackSourceException.Reason.REJECTED, result.status)
                }
                nextToken = result.token
            }
        } catch (failure: Exception) {
            // Cancellation neither registers again nor prevents a later independent user intent.
            RequestScope.ensureActive()
            if (failure !is java.util.concurrent.CancellationException) initializationFailedAt = clock()
            throw failure
        }
    }

    private fun register(): Identity {
        RequestScope.ensureActive()
        val prepared = MobileSdkRegistration.prepare(clock())
        return RequestScope.execute(http.newCall(prepared.request.tag(WireContext::class.java, WireContext(null)).build())) { response ->
            checkResponse(response)
            val bytes = boundedBytes(response, MODEL_LIMIT)
            val text = MobileSdkRegistration.responseText(bytes)
            val root = try { JSONObject(text) } catch (_: org.json.JSONException) { invalid() }
            PlaybackModels.checkStatus(root)
            val registered = DeviceRegister.parse(text) ?: invalid()
            val idPattern = Regex("[1-9][0-9]{4,29}")
            if (!idPattern.matches(registered.deviceId) || !idPattern.matches(registered.installId)) invalid()
            val cookies = MobileSdkRegistration.cookies(response.headers.values("Set-Cookie"))
            RequestScope.ensureActive()
            Identity(prepared.device + mapOf("device_id" to registered.deviceId, "iid" to registered.installId), cookies)
        }
    }

    private fun wireRequest(prepared: MobileSdkRequest, host: String, path: String, identity: Identity): Request {
        val url = try { prepared.url.toHttpUrl() } catch (_: IllegalArgumentException) { invalid() }
        if (url.scheme != "https" || url.host != host || url.port != 443 || url.encodedPath != path ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) invalid()
        if (prepared.nextLadon !in 0..0xffffffffL || prepared.body.size > MODEL_LIMIT) invalid()
        if (prepared.headers.keys.any(::accountHeader) || prepared.headers.keys.any { it.equals("Cookie2", true) }) invalid()
        val cookie = MobileSdkRegistration.cookieHeader(identity.cookies, identity.device.getValue("iid"))
        // The signer may include the exact anonymous cookie. Reject any other value, then own it here.
        val signedCookies = prepared.headers.filterKeys { it.equals("Cookie", true) }.values
        if (signedCookies.size > 1 || signedCookies.any { it != cookie }) invalid()
        val builder = Request.Builder().url(url).tag(WireContext::class.java, WireContext(cookie))
        prepared.headers.filterKeys { !it.equals("Cookie", true) && !it.equals("Accept-Encoding", true) }
            .forEach { (key, value) -> builder.header(key, value) }
        // OkHttp adds gzip itself and transparently decodes the bounded response.
        builder.header("Cookie", cookie)
        val mediaType = prepared.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.toMediaTypeOrNull()
        return builder.post(prepared.body.toRequestBody(mediaType)).build()
    }

    private fun execute(request: Request, limit: Long): ByteArray {
        RequestScope.ensureActive()
        return RequestScope.execute(http.newCall(request)) { response ->
            checkResponse(response)
            boundedBytes(response, limit)
        }
    }

    private fun checkResponse(response: Response) {
        if (response.code != 200) throw PlaybackSourceException(PlaybackSourceException.Reason.HTTP_REJECTED, response.code)
        if (!response.header("Retry-After").isNullOrBlank()) throw IOException("播放服务繁忙，请稍后重试")
    }

    private fun boundedBytes(response: Response, limit: Long): ByteArray {
        val body = response.body ?: invalid()
        if (body.contentLength() > limit) throw IOException("播放信息过大")
        val source = body.source()
        source.request(limit + 1)
        RequestScope.ensureActive()
        if (source.buffer.size > limit) throw IOException("播放信息过大")
        val bytes = source.readByteArray()
        if (bytes.isEmpty()) invalid()
        return bytes
    }

    private companion object {
        const val SDK_HOST = "mssdk.zijieapi.com"
        const val SDK_PATH = "/sdi/get_token"
        const val PLAYER_HOST = "api5-normal-sinfonlineb.fqnovel.com"
        const val PLAYER_PATH = "/novel/player/multi_video_model/player/v1"
        const val MODEL_LIMIT = 2 * 1024 * 1024L
        const val EXCHANGE_LIMIT = 128 * 1024L
        fun accountHeader(name: String): Boolean = name.lowercase() in setOf(
            "authorization", "proxy-authorization", "x-tt-token", "x-tt-passport-csrf-token", "x-ss-cookie", "x-cookie")
        fun invalid(): Nothing = throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
    }
}

/** Pure registration assembly; no saved account identity or general-purpose cookie jar. */
internal object MobileSdkRegistration {
    class Prepared(val request: Request.Builder, val device: Map<String, String>)
    private val cookieNames = setOf("odin_tt", "ttreq", "store-region", "store-region-src")
    private const val USER_AGENT = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; Xiaomi 14; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 QuicVersion:462f352c 2026-08-31)"
    private val magic = byteArrayOf(0x74, 0x63, 0x05, 0x10, 0, 0)
    private val salt = hex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58")

    fun prepare(now: Long): Prepared {
        require(now / 1000 in 0..0xffffffffL)
        val device = linkedMapOf(
            "aid" to "8662", "app_name" to "novelread", "channel" to "xiaomi_8662_64",
            "version_code" to "73932", "version_name" to "7.3.9.32", "manifest_version_code" to "73932", "update_version_code" to "73932",
            "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to "23127PN0CC",
            "device_brand" to "xiaomi", "os_api" to "34", "os_version" to "14", "resolution" to "1200*2670",
            "dpi" to "460", "ac" to "wifi", "language" to "zh", "openudid" to UUID.randomUUID().toString().replace("-", "").take(16),
            "cdid" to UUID.randomUUID().toString(), "tt_data" to "a", "_rticket" to now.toString(), "ts" to (now / 1000).toString(),
            "package" to "com.phoenix.read", "first_launch_timestamp" to (now / 1000).toString(),
            "last_deeplink_update_version_code" to "0", "cpu_support64" to "true", "host_abi" to "arm64-v8a",
            "is_guest_mode" to "0", "app_type" to "normal", "minor_status" to "0", "appTheme" to "light",
            "is_preinstall" to "0", "need_personal_recommend" to "1", "is_android_pad" to "0", "is_android_fold" to "0", "md" to "0",
            "cronet_version" to "8d40f833_2026-03-03", "ttnet_version" to "4.2.243.31-douyin", "use_store_region_cookie" to "1",
        )
        val header = JSONObject().apply {
            put("access", "wifi"); put("aid", 8662); put("app_version", "7.3.9.32"); put("app_version_minor", "")
            put("apk_first_install_time", now); put("cdid", device.getValue("cdid")); put("channel", device.getValue("channel"))
            put("clientudid", UUID.randomUUID().toString()); put("cpu_abi", "arm64-v8a"); put("density_dpi", 460)
            put("device_brand", "xiaomi"); put("device_category", "phone"); put("device_manufacturer", "Xiaomi")
            put("device_model", "Xiaomi 14"); put("device_platform", "android"); put("display_density", "xxhdpi")
            put("display_name", "红果短剧"); put("git_hash", "fe95b95"); put("guest_mode", 0); put("ipv6_list", JSONArray())
            put("is_system_app", 0); put("language", "zh"); put("manifest_version_code", 73932)
            put("not_request_sender", 0); put("oaid_may_support", true); put("openudid", device.getValue("openudid")); put("os", "Android")
            put("os_api", 34); put("os_version", "14"); put("package", "com.phoenix.read"); put("region", "CN")
            put("release_build", "UKQ1.230804.001_1701234567"); put("req_id", UUID.randomUUID().toString())
            put("resolution", "2670x1200"); put("rom", "hyperos"); put("rom_version", "OS1.0.32.0.UNCCNXM")
            put("sdk_flavor", "china"); put("sdk_target_version", 29); put("sdk_version", "3.7.3-rc.116-douyin")
            put("sig_hash", "aea615ab910015038f73c47e45d21466"); put("sim_serial_number", JSONArray())
            put("timezone", 8); put("tz_name", "Asia/Shanghai"); put("tz_offset", 28800)
            put("update_version_code", 73932); put("version_code", 73932)
            put("custom", JSONObject().put("is_android_fold", 0).put("filter_warn", 0).put("is_android_pad", 0)
                .put("client_ipv4", "0.0.0.0").put("web_ua", "Mozilla/5.0 (Linux; Android 14; Xiaomi 14 Build/UKQ1.230804.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36"))
        }
        val json = JSONObject().put("magic_tag", "ss_app_log").put("header", header).put("_gen_time", now)
        val output = ByteArrayOutputStream()
        object : GZIPOutputStream(output) { init { def.setLevel(9) } }.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        val compressed = output.toByteArray()
        byteArrayOf(31, -117, 8, 0, 0, 0, 0, 0, 0, 0).copyInto(compressed)
        val body = DeviceRegister.ttEncrypt(compressed)
        fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("*", "%2A").replace("%7E", "~")
        val query = device.toSortedMap().entries.joinToString("&") { encoded(it.key) + "=" + encoded(it.value) }
        val request = Request.Builder().url("https://log.snssdk.com/service/2/device_register/?$query")
            .header("Content-Type", "application/octet-stream;tt-data=a").header("Content-Encoding", "gzip")
            .header("User-Agent", USER_AGENT).header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .header("X-SS-STUB", digest("MD5", body).hex().uppercase()).header("X-SS-DP", "8662")
            .header("X-SS-REQ-TICKET", now.toString()).header("X-Khronos", (now / 1000).toString())
            .header("X-Argus", ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((now / 1000).toInt()).array().base64())
            .header("X-TT-Request-Tag", "t=0;n=1").header("Log-Encode-Type", "gzip").header("Passport-SDK-Version", "60755")
            .header("Passport-SDK-Settings", "device_transfer_s_0,device_transfer_ab_1").header("Sdk-Version", "2")
            .header("lc", "102").header("X-TT-Store-Region", "cn").header("X-TT-Store-Region-Src", "did")
            .header("X-VC-Bdturing-SDK-Version", "4.0.3.cn")
            .post(body.toRequestBody("application/octet-stream;tt-data=a".toMediaTypeOrNull()))
        return Prepared(request, device)
    }

    fun responseText(bytes: ByteArray): String {
        if (!bytes.take(6).toByteArray().contentEquals(magic)) return bytes.toString(Charsets.UTF_8)
        try {
            if (bytes.size < 54 || (bytes.size - 38) % 16 != 0) invalid()
            val key = digest("SHA-512", digest("SHA-512", bytes.copyOfRange(6, 38)) + salt)
            val plain = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOfRange(0, 16), "AES"), IvParameterSpec(key.copyOfRange(16, 32)))
                doFinal(bytes.copyOfRange(38, bytes.size))
            }
            if (plain.size < 64 || !java.security.MessageDigest.isEqual(plain.copyOfRange(0, 64), digest("SHA-512", plain.copyOfRange(64, plain.size)))) invalid()
            GZIPInputStream(plain.copyOfRange(64, plain.size).inputStream()).use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    RequestScope.ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 2 * 1024 * 1024) invalid()
                    output.write(buffer, 0, count)
                }
                return output.toString("UTF-8")
            }
        } catch (failure: IOException) { throw failure }
        catch (_: Exception) { invalid() }
    }

    fun cookies(headers: List<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        headers.take(32).forEach { line ->
            if (line.length > 16384 || line.any { it == '\r' || it == '\n' }) return@forEach
            val pair = line.substringBefore(';').trim()
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "").removeSurrounding("\"")
            if (name in cookieNames && value.length < 8192 && value.all { it.code in 33..126 && it != ';' && it != ',' && it != '"' && it != '\\' }) result[name] = value
        }
        if (result["store-region-src"]?.equals("uid", true) == true) invalid()
        return result.toMap()
    }

    fun cookieHeader(cookies: Map<String, String>, installId: String): String {
        val selected = linkedMapOf("install_id" to installId)
        for (name in listOf("odin_tt", "ttreq", "store-region")) cookies[name]?.takeIf { it.isNotEmpty() }?.let { selected[name] = it }
        selected["store-region-src"] = "did"
        return selected.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun invalid(): Nothing = throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
}
