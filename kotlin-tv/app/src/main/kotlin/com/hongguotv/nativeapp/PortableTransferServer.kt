// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit, short-lived LAN backup transfer. Import callback stages a TV preview, never restores. */
class PortableTransferServer(address: String, backup: String, private val validate: (String) -> Unit,
    private val onImport: (String) -> Unit, private val onExpired: () -> Unit = {}, ttlMillis: Long = 300_000) : Closeable {
    companion object { const val MAX_BYTES = 8 * 1024 * 1024 }
    private val exported = backup.toByteArray(Charsets.UTF_8).also { require(it.size in 1..MAX_BYTES) }
    private val stopped = AtomicBoolean(false)
    private val timer = Timer("portable-transfer-expiry", true)
    private val local = InetAddress.getByName(address).also { require(it is Inet4Address && (it.isLoopbackAddress || it.isSiteLocalAddress)) }
    private val listener = ServerSocket(0, 4, local).apply { soTimeout = 250 }
    @Volatile private var active: Socket? = null
    private val deadline = System.nanoTime() + ttlMillis.coerceIn(1, 300_000) * 1_000_000
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host = "${local.hostAddress}:${listener.localPort}"
    val url = "http://$host/$token"
    private var downloaded = false
    init {
        timer.schedule(object : TimerTask() { override fun run() = expire() }, ttlMillis.coerceIn(1, 300_000))
        Thread({ serve() }, "portable-transfer").apply { isDaemon = true; start() }
    }
    private fun expire() { if (stopped.compareAndSet(false, true)) { close(); onExpired() } }
    override fun close() { stopped.set(true); timer.cancel(); runCatching { listener.close() }; runCatching { active?.close() } }
    private fun serve() {
        try {
            while (!stopped.get() && System.nanoTime() < deadline) {
                val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                active = socket
                socket.use { it.soTimeout = 2000; if (!stopped.get()) runCatching { handle(it) } }
                active = null
            }
        } catch (_: Exception) { /* Lifecycle close interrupts accept/read. */ }
        finally { if (!stopped.get()) expire() else close() }
    }
    private fun line(input: InputStream, max: Int, limit: Long): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < max) {
            check(!stopped.get() && System.nanoTime() < limit) { "request expired" }
            val n = input.read(); if (n < 0) throw java.io.EOFException()
            if (n == 10) return bytes.toString("US-ASCII").trimEnd('\r')
            bytes.write(n)
        }
        error("header too large")
    }
    private fun handle(socket: Socket) {
        if (!socket.inetAddress.let { it.isLoopbackAddress || it.isSiteLocalAddress }) return
        val limit = minOf(deadline, System.nanoTime() + 15_000_000_000)
        val input = socket.getInputStream().buffered()
        val request = line(input, 1024, limit).split(' ')
        if (request.size != 3) return
        val headers = mutableMapOf<String, String>(); var size = 0
        while (true) {
            val next = line(input, 4096, limit); size += next.length + 2
            if (size > 8192) return respond(socket, 431, "请求头过大")
            if (next.isEmpty()) break
            val at = next.indexOf(':')
            if (at <= 0 || headers.put(next.substring(0, at).lowercase(Locale.ROOT), next.substring(at + 1).trim()) != null)
                return respond(socket, 400, "请求格式无效")
        }
        if (stopped.get() || System.nanoTime() >= deadline) return respond(socket, 410, "二维码已过期")
        if (headers["host"] != host || request[1] !in listOf("/$token", "/$token/download", "/$token/import")) return respond(socket, 404, "页面不存在")
        if (headers["origin"] != null && headers["origin"] != "http://$host" || headers["sec-fetch-site"] == "cross-site") return respond(socket, 403, "来源不匹配")
        if (request[0] == "GET" && request[1] == "/$token") return respond(socket, 200, page(), "text/html; charset=utf-8")
        if (request[0] == "GET" && request[1] == "/$token/download") {
            if (downloaded) return respond(socket, 410, "本次备份已下载，请在电视上重新打开二维码")
            respond(socket, 200, exported, "application/json; charset=utf-8", "Content-Disposition: attachment; filename=hongguotv-backup.json\r\n")
            downloaded = true; return
        }
        if (request[0] != "POST" || request[1] != "/$token/import") return respond(socket, 405, "不支持的操作")
        if (headers.containsKey("transfer-encoding") || headers["content-type"]?.substringBefore(';') != "application/json") return respond(socket, 400, "请选择 JSON 备份文件")
        val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..MAX_BYTES } ?: return respond(socket, 413, "备份不能超过 8 MB")
        val bytes = ByteArray(length); var offset = 0
        while (offset < length) {
            if (stopped.get() || System.nanoTime() >= limit) return respond(socket, 408, "上传超时")
            val count = input.read(bytes, offset, minOf(8192, length - offset)); if (count < 0) return
            offset += count
        }
        val raw = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().also(validate)
        }.getOrNull() ?: return respond(socket, 400, "备份内容无效，请选择本应用导出的 JSON 文件")
        if (stopped.get() || System.nanoTime() >= deadline) return respond(socket, 410, "二维码已过期")
        respond(socket, 200, "已发送到电视。请用遥控器确认恢复；确认前不会更改记录。")
        if (stopped.get()) return
        close()
        onImport(raw)
    }
    private fun page() = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>红果 TV · 备份与恢复</title><style>body{font:18px sans-serif;background:#10131b;color:#f4f5f8;max-width:540px;margin:30px auto;padding:20px;line-height:1.7}a,button{display:block;padding:14px;border:0;border-radius:8px;background:#ff8964;color:#18212d;text-decoration:none;text-align:center;font:inherit;margin:16px 0}input{font:inherit;max-width:100%}button{width:100%}</style><h1>备份与恢复</h1><p>备份仅在手机与电视之间传输，不包含账号登录与设备标识。完整导出可能包含私人片源接口，请妥善保管文件。</p><a href="/$token/download" download>下载电视备份（仅一次）</a><h2>从文件恢复</h2><input id="file" type="file" accept=".json,application/json"><button id="send">发送到电视确认</button><p id="status">最多 8 MB；需要在电视上确认，才会合并记录。</p><p>关闭电视窗口即失效，最长 5 分钟。</p><script nonce="$token">document.getElementById('send').onclick=function(){var f=document.getElementById('file').files[0],s=document.getElementById('status'),b=this;if(!f||f.size>8388608){s.textContent='请选择不超过 8 MB 的 JSON 文件';return}b.disabled=true;s.textContent='正在上传并验证…';fetch('/$token/import',{method:'POST',headers:{'Content-Type':'application/json'},body:f}).then(function(r){return r.text().then(function(t){s.textContent=t;if(!r.ok)b.disabled=false})}).catch(function(){s.textContent='连接已失效，请在电视上重新打开二维码';b.disabled=false})};</script></html>"""
    private fun respond(socket: Socket, code: Int, text: String, type: String = "text/plain; charset=utf-8") = respond(socket, code, text.toByteArray(Charsets.UTF_8), type)
    private fun respond(socket: Socket, code: Int, body: ByteArray, type: String, extra: String = "") {
        val header = "HTTP/1.1 $code Response\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$token'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n$extra\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}
