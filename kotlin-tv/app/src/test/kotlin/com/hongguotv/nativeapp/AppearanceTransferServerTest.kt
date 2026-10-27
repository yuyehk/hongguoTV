package com.hongguotv.nativeapp

import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AppearanceTransferServerTest {
    private fun send(url: String, method: String, body: ByteArray = byteArrayOf(), extra: String = "", path: String? = null): String {
        val uri = URI(url)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 2000
            socket.getOutputStream().apply {
                write(("$method ${path ?: uri.path} HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n" + extra +
                    "Content-Length: ${body.size}\r\nContent-Type: image/jpeg\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                write(body); flush()
            }
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
    @Test fun nonceOriginAndBodyLimitsProtectUploadAndSuccessIsOneShot() {
        val uploaded = AtomicInteger()
        val received = CountDownLatch(1)
        val server = AppearanceTransferServer("127.0.0.1", { bytes -> assertArrayEquals(byteArrayOf(1, 2, 3), bytes); uploaded.incrementAndGet(); received.countDown() })
        try {
            assertTrue(send(server.url, "GET").contains("选择背景图片"))
            assertTrue(send(server.url, "GET", path = "/guess").startsWith("HTTP/1.1 404"))
            assertTrue(send(server.url + "/upload", "POST", byteArrayOf(1), "Origin: http://attacker.test\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(send(server.url + "/upload", "POST", byteArrayOf(1), "Transfer-Encoding: chunked\r\n").startsWith("HTTP/1.1 400"))
            assertEquals(0, uploaded.get())
            assertTrue(send(server.url + "/upload", "POST", byteArrayOf(1, 2, 3)).startsWith("HTTP/1.1 200"))
            assertTrue(received.await(1, TimeUnit.SECONDS))
            assertEquals(1, uploaded.get())
        } finally { server.close() }
        assertTrue(runCatching { send(server.url, "GET") }.isFailure)
    }
    @Test fun closeAndExpiryInvalidateEndpointAndFailedImagesCanRetry() {
        val expires = CountDownLatch(1)
        val server = AppearanceTransferServer("127.0.0.1", { error("bad image") }, { expires.countDown() }, 500)
        try {
            assertTrue(send(server.url + "/upload", "POST", byteArrayOf(1)).startsWith("HTTP/1.1 400"))
            assertTrue(expires.await(3, TimeUnit.SECONDS))
            assertTrue(runCatching { send(server.url, "GET") }.isFailure)
        } finally { server.close() }
        val cancelled = AppearanceTransferServer("127.0.0.1", {})
        cancelled.close()
        assertTrue(runCatching { send(cancelled.url, "GET") }.isFailure)
    }
    @Test fun declaredOversizeRejectedWithoutReadingPayload() {
        val server = AppearanceTransferServer("127.0.0.1", { error("must not import") })
        try {
            val uri = URI(server.url)
            Socket(uri.host, uri.port).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write("POST ${uri.path}/upload HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nContent-Type: image/jpeg\r\nContent-Length: 8388609\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 413"))
            }
        } finally { server.close() }
    }
}
