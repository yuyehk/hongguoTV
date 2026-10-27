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
import java.security.SecureRandom
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

/** Explicitly opened, one-shot, private-interface-only image receiver. Never starts with the app. */
class AppearanceTransferServer(address: String, private val onUpload: (ByteArray) -> Unit,
    private val onExpired: () -> Unit = {}, ttlMillis: Long = 300_000,
    private val onCompleted: () -> Unit = {}) : Closeable {
    private val stopped = AtomicBoolean(false)
    private val timer = Timer("appearance-expiry", true)
    private val local = InetAddress.getByName(address).also {
        require(it is Inet4Address && (it.isLoopbackAddress || it.isSiteLocalAddress))
    }
    private val listener = ServerSocket(0, 4, local).apply { soTimeout = 250 }
    @Volatile private var active: Socket? = null
    private val deadline = System.nanoTime() + ttlMillis.coerceIn(1, 300_000) * 1_000_000
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host = "${local.hostAddress}:${listener.localPort}"
    val url = "http://$host/$token"
    init {
        timer.schedule(object : TimerTask() { override fun run() = expire() }, ttlMillis.coerceIn(1, 300_000))
        Thread({ serve() }, "appearance-upload").apply { isDaemon = true; start() }
    }
    private fun expire() {
        if (stopped.compareAndSet(false, true)) { close(); onExpired() }
    }
    override fun close() {
        stopped.set(true); timer.cancel()
        runCatching { listener.close() }; runCatching { active?.close() }
    }
    private fun serve() {
        try {
            while (!stopped.get() && System.nanoTime() < deadline) {
                val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                active = socket
                socket.use { it.soTimeout = 2000; if (!stopped.get()) runCatching { handle(it) } }
                active = null
            }
        } catch (_: Exception) { /* Close interrupts blocking I/O. */ }
        finally { if (!stopped.get()) expire() else close() }
    }
    private fun line(input: InputStream, max: Int): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < max) {
            val n = input.read()
            if (n < 0) throw java.io.EOFException()
            if (n == 10) return bytes.toString("US-ASCII").trimEnd('\r')
            bytes.write(n)
        }
        error("header too large")
    }
    private fun handle(socket: Socket) {
        if (!socket.inetAddress.let { it.isLoopbackAddress || it.isSiteLocalAddress }) return
        val input = socket.getInputStream().buffered()
        val request = line(input, 1024).split(' ')
        if (request.size != 3) return
        val headers = mutableMapOf<String, String>()
        var size = 0
        while (true) {
            val next = line(input, 4096); size += next.length + 2
            if (size > 8192) return respond(socket, 431, "请求头过大")
            if (next.isEmpty()) break
            val at = next.indexOf(':')
            if (at < 1 || headers.put(next.substring(0, at).lowercase(Locale.ROOT), next.substring(at + 1).trim()) != null)
                return respond(socket, 400, "请求格式无效")
        }
        if (stopped.get() || System.nanoTime() >= deadline) return respond(socket, 410, "二维码已过期")
        if (headers["host"] != host || request[1] !in listOf("/$token", "/$token/upload")) return respond(socket, 404, "页面不存在")
        if (headers["origin"] != null && headers["origin"] != "http://$host" || headers["sec-fetch-site"] == "cross-site")
            return respond(socket, 403, "来源不匹配")
        if (request[0] == "GET" && request[1] == "/$token") return respond(socket, 200, page(), "text/html; charset=utf-8")
        if (request[0] != "POST" || request[1] != "/$token/upload") return respond(socket, 405, "不支持的操作")
        if (headers.containsKey("transfer-encoding") || headers["content-type"]?.substringBefore(';') !in setOf("image/jpeg", "image/png", "image/webp", "application/octet-stream"))
            return respond(socket, 400, "请选择 JPEG、PNG 或 WebP 图片")
        val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..AppearanceImages.MAX_UPLOAD }
            ?: return respond(socket, 413, "图片不能超过 8 MB")
        val bytes = ByteArray(length)
        val uploadDeadline = minOf(deadline, System.nanoTime() + 15_000_000_000)
        var offset = 0
        while (offset < length) {
            if (stopped.get() || System.nanoTime() >= uploadDeadline) return respond(socket, 408, "上传超时")
            val n = input.read(bytes, offset, minOf(8192, length - offset))
            if (n < 0) return
            offset += n
        }
        if (stopped.get() || System.nanoTime() >= deadline) return respond(socket, 410, "二维码已过期")
        try { onUpload(bytes) }
        catch (_: Exception) { return respond(socket, 400, "图片无效；支持不超过 1600 万像素的 JPEG、PNG 或 WebP") }
        try { respond(socket, 200, "背景已保存到电视。本次二维码已失效。") }
        finally { close(); onCompleted() }
    }
    private fun page() = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>电视背景图片</title><style>body{font:18px sans-serif;max-width:540px;margin:30px auto;padding:20px;background:#10131b;color:#f4f5f8;line-height:1.7}input,button{font:inherit;max-width:100%;margin:12px 0}button{display:block;padding:12px;background:#fa8059;border:0;border-radius:8px}</style><h1>选择背景图片</h1><p>手机与电视需连接同一局域网。支持 JPEG、PNG、WebP，最多 8 MB、1600 万像素。仅保存背景，不更改观看记录。</p><input id="file" type="file" accept="image/jpeg,image/png,image/webp"><button id="send">设为电视背景</button><p id="status">关闭电视窗口即失效，最长 5 分钟。</p><script nonce="$token">document.getElementById('send').onclick=function(){var f=document.getElementById('file').files[0],s=document.getElementById('status'),b=this;if(!f||f.size>8388608){s.textContent='请选择不超过 8 MB 的图片';return}b.disabled=true;s.textContent='正在上传与处理…';fetch('/$token/upload',{method:'POST',headers:{'Content-Type':f.type||'application/octet-stream'},body:f}).then(function(r){return r.text().then(function(t){s.textContent=t;if(!r.ok)b.disabled=false})}).catch(function(){s.textContent='连接已失效，请在电视上重新打开二维码';b.disabled=false})};</script></html>"""
    private fun respond(socket: Socket, code: Int, message: String, type: String = "text/plain; charset=utf-8") {
        val body = message.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code Response\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$token'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}
