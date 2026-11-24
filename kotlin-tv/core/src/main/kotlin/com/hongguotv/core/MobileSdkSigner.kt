// SPDX-License-Identifier: GPL-3.0-only
// SDK session protocol adapted from jackotom/hongguo-drama ea5a852f65acdc4c5b11265ca13b03d115096dc7.
// All identity and token inputs are issued to this anonymous playback session; no account storage.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream

internal class MobileSdkRequest(val url: String, val headers: Map<String, String>,
    val body: ByteArray, val nextLadon: Long) {
    override fun toString() = "MobileSdkRequest(redacted)"
}

internal interface MobileSdkRequestFactory {
    fun bootstrap(device: Map<String, String>, cookies: Map<String, String>, token: String?, previousLadon: Long): MobileSdkRequest
    fun playback(id: String, device: Map<String, String>, cookies: Map<String, String>, token: String, previousLadon: Long): MobileSdkRequest
}

/** Pure JVM SDK signing, selecting the independently oracle-verified digest branch 12. */
internal class MobileSdkSigner(
    private val clock: () -> Long = System::currentTimeMillis,
    private val entropy: (Int) -> ByteArray = { count -> ByteArray(count).also(SecureRandom()::nextBytes) },
    private val processId: Long = 10000L + SecureRandom().nextInt(20000),
) : MobileSdkRequestFactory {
    private val legacy = MobileV5Signer(clock = clock, random = { u32() / 4294967296.0 }, processNonce = "sdk-session")
    private fun bytes(count: Int) = entropy(count).also { require(it.size == count) }
    private fun u32() = bytes(4).i32(0, true).toLong() and 0xffffffffL

    override fun bootstrap(device: Map<String, String>, cookies: Map<String, String>, token: String?, previousLadon: Long): MobileSdkRequest {
        val params = profile(device, cookies)
        validateToken(token, nullable = true)
        val plaintext = devicePayload(params, token)
        val nonce = bytes(4)
        val entropyByte = bytes(1)[0].u()
        val now = clock()
        for (offset in 0..1023) {
            RequestScope.ensureActive()
            val ms = now + offset
            val query = bootstrapQuery(params, ms)
            val body = MobileSdkExchange.request(plaintext, ms, MobileSdkMaterials.exchangeKey,
                MobileSdkMaterials.exchangeInnerKey, nonce, entropyByte)
            if (legacy.branch(query, body, seconds(ms)) != 0) continue
            return signPrepared(params, cookies, query, body, ms, token, previousLadon, bootstrap = true)
        }
        throw java.io.IOException("无法准备播放会话签名")
    }

    override fun playback(id: String, device: Map<String, String>, cookies: Map<String, String>, token: String, previousLadon: Long): MobileSdkRequest {
        require(acceptsEpisodeId(id))
        val params = profile(device, cookies)
        validateToken(token, nullable = false)
        val json = """{"dr_scene":"player","mixed_video_id_map":{"1004":["$id"]},"biz_param":{"detail_page_version":0,"device_level":4,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024}}"""
        val body = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val now = clock()
        for (offset in 0..1023) {
            RequestScope.ensureActive()
            val ms = now + offset
            val values = params + ("_rticket" to ms.toString())
            val query = MobileV5Signer.encode(PLAYBACK_KEYS.mapNotNull { key ->
                values[key]?.takeIf { it.isNotEmpty() }?.let { key to it }
            }.toMap(LinkedHashMap()))
            if (legacy.branch(query, body, seconds(ms)) != 0) continue
            val signed = signPrepared(params, cookies, query, body, ms, token, previousLadon, bootstrap = false)
            val headers = signed.headers.toMutableMap().apply {
                put("Content-Type", "application/json; charset=utf-8")
                put("Content-Encoding", "gzip")
                put("Accept", "application/json")
                put("X-Reading-Request", "$ms-${100000000L + u32() % 900000000L}")
                put("X-TT-Store-Region", cookies["store-region"].orEmpty().ifEmpty { "cn" })
                put("X-TT-Store-Region-Src", "did")
                put("X-SS-REQ-TICKET", ms.toString())
            }
            return MobileSdkRequest(signed.url, headers, body, signed.nextLadon)
        }
        throw java.io.IOException("无法准备本集播放签名")
    }

    internal fun signPrepared(device: Map<String, String>, cookies: Map<String, String>, query: String,
        body: ByteArray, milliseconds: Long, token: String?, previousLadon: Long, bootstrap: Boolean): MobileSdkRequest {
        val params = profile(device, cookies)
        validateToken(token, nullable = bootstrap)
        val ts = seconds(milliseconds)
        require(body.size in 1..16_777_216 && query.length <= 1_048_576 && previousLadon in 0..0xffffffffL)
        require(legacy.branch(query, body, ts) == 0)
        val queryHash = sm3(query.toByteArray(Charsets.UTF_8))
        val proof = legacy.hashF13(queryHash, digest("MD5", body), ts)
        val flags = le64(if (bootstrap) 2L else 0x0504L)
        val counters = proto(1 to 2L, 2 to 1388734L, 3 to 1388734L, 5 to 1388734L)
        val environment = proto(1 to 2L, 6 to "v04.10.03.04-bugfix", 7 to processId * 2,
            8 to params["channel"].orEmpty(), 12 to boundDeviceProof(params), 14 to params.getValue("version_name"),
            15 to 4228L, 19 to milliseconds * 2)
        val state = """{"cmr":16777216,"cmr2":16777216,"vpn":0,"kd":0,"dyn":"","do":0,"tk":${token != null}}"""
        val nonce0 = u32()
        val nonce1 = u32().toInt()
        val nonce2 = u32().toInt()
        val message = proto(1 to MobileV5Constants.magic, 2 to 10L, 3 to nonce0 * 2,
            4 to "8662", 5 to params.getValue("device_id"), 6 to "646637794", 7 to params.getValue("version_name"),
            8 to "v04.10.03-ml-android", 9 to 0x040a0300L * 2, 10 to flags, 12 to (ts.toLong() and 0xffffffffL) * 2,
            13 to proof, 14 to queryHash.copyOf(6), 15 to counters, 16 to token.orEmpty(),
            17 to (ts.toLong() and 0xffffffffL) * 2, 20 to "none", 21 to 738L, 23 to environment, 24 to state)
        val medusa = legacy.sealSdk(message, queryHash, proof, ts, flags, nonce1, nonce2)
        val ladon = advanceLadon(previousLadon, bytes(8).i64(0, true) and -16L, ts)
        val gorgonNonce = bytes(2).let { it[0].u() or (it[1].u() shl 8) }
        val headers = linkedMapOf("Content-Type" to "application/octet-stream", "Accept" to "*/*",
            "X-TT-Request-Tag" to "t=0;n=0", "X-SS-DP" to "8662", "X-Khronos" to (ts.toLong() and 0xffffffffL).toString(),
            "X-Argus" to le32(ts).base64(), "X-Ladon" to be32(ladon.toInt()).base64(),
            "X-Gorgon" to gorgon(query.toByteArray(Charsets.UTF_8), body, ts, gorgonNonce, flags.copyOf(2)),
            "X-Helios" to legacy.helios(ts), "X-Medusa" to medusa,
            "X-SS-STUB" to digest("MD5", body).hex().uppercase(), "X-VC-Bdturing-SDK-Version" to "4.0.3.cn",
            "X-TT-Trace-ID" to "00-${bytes(16).hex()}-${bytes(8).hex()}-01",
            "User-Agent" to "com.phoenix.read/74332 (Linux; U; Android ${params.getValue("os_version")}; zh_CN; ${params.getValue("device_type")}; Cronet/TTNetVersion:8d40f833_2026-03-03)")
        val selected = linkedMapOf("install_id" to params.getValue("iid"))
        for (name in listOf("odin_tt", "ttreq", "store-region")) cookies[name]?.takeIf { it.isNotEmpty() }?.let { value ->
            require(value.length <= 8192 && value.all { it.code in 33..126 && it != ';' })
            selected[name] = value
        }
        selected["store-region-src"] = "did"
        headers["Cookie"] = selected.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return MobileSdkRequest((if (bootstrap) BOOTSTRAP_URL else PLAYBACK_URL) + "?" + query, headers, body, ladon)
    }

    private fun profile(device: Map<String, String>, cookies: Map<String, String>): Map<String, String> {
        require(device["aid"] == "8662" && cookies["store-region-src"] != "uid")
        for (name in listOf("device_id", "iid")) require(device[name]?.matches(Regex("[1-9][0-9]{4,29}")) == true)
        for (name in listOf("cdid", "device_type", "device_brand", "os_version", "os_api", "resolution", "dpi")) {
            require(device[name]?.let { it.isNotEmpty() && it.length <= 256 && it.all { ch -> ch.code in 32..126 } } == true)
        }
        require(device.values.all { it.length <= 8192 && it.all { ch -> ch.code in 32..126 } })
        return device + mapOf("version_code" to "74332", "version_name" to "7.4.3.32",
            "manifest_version_code" to "74332", "update_version_code" to "74332", "device_platform" to "android", "os" to "android")
    }

    private fun validateToken(token: String?, nullable: Boolean) {
        require((nullable && token == null) || (token != null && token.length in 1..256 && token.all { it.code in 33..126 }))
    }

    private fun seconds(milliseconds: Long): Int {
        require(milliseconds >= 0 && milliseconds / 1000 <= 0xffffffffL)
        return (milliseconds / 1000).toInt()
    }

    private fun bootstrapQuery(params: Map<String, String>, milliseconds: Long): String {
        val values = linkedMapOf("lc_id" to "646637794", "platform" to "android", "device_platform" to "android",
            "sdk_ver" to "v04.10.03-ml-android", "sdk_ver_code" to 0x040a0300.toString(),
            "app_ver" to params.getValue("version_name"), "version_code" to params.getValue("version_code"),
            "aid" to "8662", "sdkid" to params["sdkid"].orEmpty(), "subaid" to params["subaid"].orEmpty(),
            "iid" to params.getValue("iid"), "did" to params.getValue("device_id"), "bd_did" to params["bd_did"].orEmpty(),
            "client_type" to "inhouse", "region_type" to "ml", "mode" to "2", "full_update_version_code" to params.getValue("update_version_code"))
        for (key in BOOTSTRAP_KEYS) values[key] = when (key) {
            "_rticket" -> milliseconds.toString()
            "need_personal_recommend" -> "1"
            else -> params[key].orEmpty()
        }
        return MobileV5Signer.encode(values)
    }

    private fun devicePayload(params: Map<String, String>, token: String?): ByteArray {
        val marker = MobileSdkMaterials.unavailable
        val values = sortedMapOf<Int, Any>()
        for (tag in intArrayOf(1,19,20,23,28,31,32,38,39,50,53,93,94,95,96,109,110,111,115,135,2000,2001)) values[tag] = marker
        for (tag in intArrayOf(13,29,30,36,37,41,42,43,49,51,52,56,57,58,59,60,61,62,63,64,66,67,69,70,71,72,73,74,75,76,77,78,79,80,81,82,83,84,85,86,87,88,97,98,99,100,101,102,103,104,105,106,113,114,116,117,118)) values[tag] = 1999997L
        for ((tag, key) in listOf(2 to "device_brand",3 to "device_type",6 to "os_version",7 to "resolution",9 to "rom_version")) values[tag] = params[key].orEmpty().ifEmpty { marker }
        values[5] = "Android"; values[12] = "Asia/Shanghai,8"
        for ((tag, key) in listOf(8 to "dpi", 48 to "os_api")) values[tag] = params[key]?.toLongOrNull()?.takeIf { it >= 0 }?.times(2) ?: 1999997L
        val fields = mutableListOf<Pair<Int, Any>>(1 to proto(*values.entries.map { it.key to it.value }.toTypedArray()))
        if (token != null) fields += 2 to token
        fields += listOf(3 to "android",4 to "v04.10.03-ml-android",5 to 0x040a0300L * 2,
            6 to "8662",7 to params.getValue("version_name"),8 to params.getValue("device_id"),
            11 to 1999997L,16 to 1999997L,17 to marker)
        return proto(*fields.toTypedArray())
    }

    private fun boundDeviceProof(params: Map<String, String>): ByteArray = proto(
        1 to 2L, 2 to 4L, 3 to "8662", 4 to params.getValue("device_id"), 5 to params.getValue("cdid"),
        6 to "!noperm!",7 to 1777775L,8 to 1777775L,9 to 6L,10 to 1777775L,11 to "!notset!",
        12 to "Asia/Shanghai,8",13 to "zh_CN",14 to 8L,
        16 to 255.24993896484375f,17 to 35.58599090576172f,18 to 3.467449188232422f,
        19 to 3.467449188232422f,20 to 255.1754913330078f,21 to 42.17544174194336f,
        22 to params.getValue("os_version"),23 to 82L,24 to 72L,
        25 to 3456776033270L,26 to 3456776033270L,27 to 3456776033270L,28 to 3456776033274L,29 to 1L,
        30 to params.getValue("device_type"),31 to params.getValue("device_brand"),32 to params.getValue("device_type"),
        33 to params.getValue("device_type"),34 to params.getValue("device_brand"),35 to params.getValue("device_brand"),
        36 to params.getValue("device_brand"),38 to 62L)

    companion object {
        internal fun acceptsEpisodeId(id: String) = id.matches(Regex("[1-9][0-9]{7,23}"))
        const val BOOTSTRAP_URL = "https://mssdk.zijieapi.com/sdi/get_token"
        const val PLAYBACK_URL = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/player/v1"
        private val PLAYBACK_KEYS = ("device_platform os ssmix _rticket cdid channel aid app_name version_code version_name manifest_version_code update_version_code resolution dpi device_type device_brand language os_api os_version ac host_abi dragon_device_type pv_player compliance_status need_personal_recommend player_so_load is_android_pad_screen rom_version device_id iid klink_egdi").split(' ')
        private val BOOTSTRAP_KEYS = ("os ssmix _rticket cdid channel app_name version_name manifest_version_code update_version_code resolution dpi device_type device_brand language os_api os_version ac host_abi dragon_device_type pv_player compliance_status need_personal_recommend player_so_load is_android_pad_screen rom_version device_id klink_egdi").split(' ')

        internal fun advanceLadon(previous: Long, allocation: Long, seconds: Int): Long {
            require(previous in 0..0xffffffffL)
            var crc = 0L
            for (byte in "646637794".toByteArray()) {
                crc = crc xor byte.u().toLong()
                repeat(8) { crc = (crc ushr 1) xor if (crc and 1L != 0L) 0x95ac9329ac4bc9b5UL.toLong() else 0L }
            }
            return previous xor (java.lang.Long.rotateLeft(allocation xor crc xor (seconds.toLong() and 0xffffffffL), 4) and 0xffffffffL)
        }

        internal fun gorgon(query: ByteArray, body: ByteArray, seconds: Int, nonce: Int, flags: ByteArray): String {
            require(nonce in 0..65535 && flags.size == 2)
            val input = digest("MD5", query).copyOf(4) + (if (body.isEmpty()) ByteArray(4) else digest("MD5", body).copyOf(4)) +
                ByteArray(4) + hex("00030a04") + be32(seconds)
            val key = byteArrayOf(0x4a, flags[0], 0x16, (nonce ushr 8).toByte(), 0x47, 0x6c, flags[1], nonce.toByte())
            val state = IntArray(256) { it }; var j = 0
            for (i in 0..255) { j = (j + state[i] + key[i % 8].u()) and 255; val old = state[i]; state[i] = state[j]; state[j] = old }
            var i = 0; j = 0
            val output = input.map { byte ->
                i = (i + 1) and 255; j = (j + state[i]) and 255
                val old = state[i]; state[i] = state[j]; state[j] = old
                byte.u() xor state[(state[i] + state[j]) and 255]
            }.toIntArray()
            for (at in output.indices) {
                val swapped = ((output[at] ushr 4) or (output[at] shl 4)) and 255
                val mixed = output[(at + 1) % output.size] xor swapped
                output[at] = ((Integer.reverse(mixed) ushr 24) xor 20).inv() and 255
            }
            return (hex("8404") + byteArrayOf(nonce.toByte(), (nonce ushr 8).toByte()) + flags + output.map { it.toByte() }.toByteArray()).hex()
        }

        private fun proto(vararg fields: Pair<Int, Any>): ByteArray = ByteArrayOutputStream().apply {
            fun varint(value: Long) {
                var remaining = value
                while (remaining and -128L != 0L) { write(((remaining and 127) or 128).toInt()); remaining = remaining ushr 7 }
                write(remaining.toInt())
            }
            for ((tag, value) in fields) when (value) {
                is Float -> { varint((tag.toLong() shl 3) or 5); write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()) }
                is Number -> { require(value.toLong() >= 0); varint(tag.toLong() shl 3); varint(value.toLong()) }
                else -> {
                    val raw = if (value is ByteArray) value else value.toString().toByteArray(Charsets.UTF_8)
                    if (raw.isNotEmpty()) { varint((tag.toLong() shl 3) or 2); varint(raw.size.toLong()); write(raw) }
                }
            }
        }.toByteArray()
    }
}
