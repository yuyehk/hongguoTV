package com.hongguotv.nativeapp

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Temporary nonce-protected paste endpoint. No URL fetching, scripts, cookies or account state. */
class SourceImportServer(private val receive: (String) -> Int) : Closeable {
    private val listener = ServerSocket(0, 4).apply { soTimeout = 500 }
    val port = listener.localPort
    val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4)) { task -> Thread(task, "source-import-client").apply { isDaemon = true } }
    @Volatile private var closed = false
    private val receiveLock = Any()
    private val acceptor = Thread({
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        while (!closed && System.nanoTime() < deadline) {
            try {
                val socket = listener.accept().apply { soTimeout = 5000 }; sockets += socket
                try { workers.execute { serve(socket) } } catch (_: java.util.concurrent.RejectedExecutionException) { sockets -= socket; socket.close() }
            } catch (_: SocketTimeoutException) { } catch (_: java.io.IOException) { break }
        }
        close()
    }, "source-import-accept").apply { isDaemon = true; start() }
    private fun serve(socket: Socket) {
        try { socket.use { connection ->
            val input = connection.getInputStream()
            val bytes = ByteArrayOutputStream()
            var tail = 0
            while (bytes.size() < 8192 && tail != 0x0d0a0d0a) { val b = input.read(); if (b < 0) return; bytes.write(b); tail = (tail shl 8) or b }
            if (tail != 0x0d0a0d0a) { respond(connection, 413, "请求头过大"); return }
            val lines = bytes.toString("US-ASCII").split("\r\n")
            val request = lines.first().split(' ')
            val path = request.getOrNull(1)
            if (path != "/$nonce" && path != "/$nonce/import") { respond(connection, 404, "入口已失效"); return }
            if (request.first() == "GET" && path == "/$nonce") {
                respond(connection, 200, """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>片源导入</title><h2>粘贴 MacCMS JSON 配置</h2><p>只支持 sites/sources 中的 type=1 HTTP(S) 接口。不会下载或执行脚本、JAR。接口凭据保存在电视本机。入口五分钟后关闭。</p><textarea id="config" rows="15" style="width:95%"></textarea><p><button onclick="send()">导入电视</button></p><pre id="status"></pre><script>async function send(){try{const r=await fetch(location.pathname+'/import',{method:'POST',headers:{'Content-Type':'application/json'},body:document.getElementById('config').value});document.getElementById('status').textContent=await r.text()}catch(e){document.getElementById('status').textContent='连接已关闭，请在电视重新打开入口'}}</script>""", true)
                return
            }
            if (request.first() != "POST" || path != "/$nonce/import") { respond(connection, 405, "方法不支持"); return }
            val headers = lines.drop(1).filter { it.contains(':') }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            if (headers.containsKey("transfer-encoding")) { respond(connection, 400, "不支持分块上传"); return }
            val length = headers["content-length"]?.toLongOrNull()
            if (length == null || length < 0) { respond(connection, 411, "缺少请求长度"); return }
            if (length > ThirdCms.MAX_BYTES) { respond(connection, 413, "配置超过 2 MB"); return }
            if (headers["content-type"]?.substringBefore(';') != "application/json") { respond(connection, 415, "需要 JSON 配置"); return }
            val body = ByteArray(length.toInt()); var read = 0
            while (read < body.size) { val count = input.read(body, read, body.size-read); if (count < 0) return; read += count }
            val text = String(body, Charsets.UTF_8)
            if (text.contains('\uFFFD')) { respond(connection, 400, "请使用 UTF-8 JSON"); return }
            val result = runCatching { synchronized(receiveLock) { check(!closed); receive(text) } }
            if (result.isSuccess) respond(connection, 200, "导入完成，新增 ${result.getOrThrow()} 个兼容片源。")
            else respond(connection, 400, "JSON 不兼容。仅支持普通 MacCMS 接口，最多 2 MB。")
        } } catch (_: java.io.IOException) { } finally { sockets -= socket; runCatching { socket.close() } }
    }
    private fun respond(socket: Socket, status: Int, message: String, html: Boolean = false) {
        val body = message.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status Response\r\nContent-Type: ${if (html) "text/html" else "text/plain"}; charset=utf-8\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            write(body); flush()
        }
    }
    override fun close() {
        synchronized(receiveLock) { closed = true }; runCatching { listener.close() }
        sockets.toList().forEach { runCatching { it.close() } }; workers.shutdownNow()
    }
}
