// SPDX-License-Identifier: GPL-3.0-only
// Player protocol adapted from N3urda/hongguoTV 6672b55 and tsukiyo-zero/hongguoTV 0236976.
package com.hongguotv.core

import java.io.IOException
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject

/** Lazy, repository-owned anonymous playback identity. Never reads/mutates account or global identity. */
internal class PlaybackSession(
    http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val signer: MobilePlaybackSigner = MobilePlayback.signer,
) {
    private val http = MobilePlayback.anonymousHttp(http)
    private val lock = Object()
    private var identity: Map<String, String>? = null
    private var registering = false
    private var failureAt: Long? = null
    // Rate-limit recovery itself, including the window before the new identity is validated.
    private var recoveryStartedAt: Long? = null
    private val cooldownMs = 30_000L

    fun device(): Map<String, String> = acquire(null)

    fun model(id: String, allowIdentityRecovery: Boolean = true): JSONObject {
        require(id.matches(Regex("[0-9]{1,30}")))
        val initial = device()
        return try {
            MobilePlayback.model(id, initial, http, signer, clock())
        } catch (empty: EmptyPlaybackResponseException) {
            RequestScope.ensureActive()
            if (!allowIdentityRecovery) throw empty
            // Concurrent calls that failed on the same identity join one refresh.
            val fresh = acquire(initial)
            try {
                MobilePlayback.model(id, fresh, http, signer, clock())
            } catch (again: EmptyPlaybackResponseException) {
                synchronized(lock) {
                    // An older response must not cool down an already replaced identity.
                    if (identity === fresh) failureAt = clock()
                }
                throw again
            }
        }
    }

    fun ladder(id: String, allowIdentityRecovery: Boolean = true): StreamLadder =
        MobilePlayback.ladder(model(id, allowIdentityRecovery))

    private fun acquire(rejected: Map<String, String>?): Map<String, String> {
        synchronized(lock) {
            while (true) {
                RequestScope.ensureActive()
                val current = identity
                if (current != null && (rejected == null || current !== rejected)) return current
                // Join the active registration before checking its recovery budget.
                if (registering) {
                    try { lock.wait(100) } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("请求已取消", interrupted)
                    }
                    continue
                }
                val now = clock()
                val failed = failureAt
                val started = recoveryStartedAt
                if ((failed != null && now - failed < cooldownMs) ||
                    (rejected != null && started != null && now - started < cooldownMs)) {
                    throw IOException("播放会话恢复暂不可用，请稍后重试")
                }
                registering = true
                if (rejected != null) recoveryStartedAt = now
                break
            }
        }
        try {
            val registered = register()
            RequestScope.ensureActive()
            synchronized(lock) {
                identity = registered
                failureAt = null
            }
            return registered
        } catch (failure: Exception) {
            // A cancelled request is not an identity rejection; do not poison the session.
            RequestScope.ensureActive()
            if (failure is java.util.concurrent.CancellationException || Thread.currentThread().isInterrupted) throw failure
            synchronized(lock) { failureAt = clock() }
            throw failure
        } finally {
            synchronized(lock) { registering = false; lock.notifyAll() }
        }
    }

    private fun register(): Map<String, String> {
        RequestScope.ensureActive()
        val device = linkedMapOf("cdid" to UUID.randomUUID().toString(),
            "openudid" to UUID.randomUUID().toString().replace("-", "").take(16),
            "device_type" to "PGT-AN10", "device_brand" to "OPPO", "os_version" to "12",
            "os_api" to "32", "channel" to "vivo_8662_64", "host_abi" to "arm64-v8a")
        val now = clock()
        val body = DeviceRegister.payload(device, now)
        val request = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, device, now).forEach { (key, value) -> request.header(key, value) }
        request.post(body.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw PlaybackSourceException(PlaybackSourceException.Reason.HTTP_REJECTED, response.code)
            val text = MobilePlayback.boundedText(response, 256 * 1024L)
            val registered = DeviceRegister.parse(text)
                ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            val numericIdentity = Regex("[0-9]{1,30}")
            if (!numericIdentity.matches(registered.deviceId) || !numericIdentity.matches(registered.installId) ||
                registered.deviceId.all { it == '0' } || registered.installId.all { it == '0' }) {
                throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            }
            // Deliberately discard all registration cookies and device tokens, too.
            device + mapOf("device_id" to registered.deviceId, "iid" to registered.installId,
                "install_id" to registered.installId)
        }
    }
}
