package com.hongguotv.nativeapp

import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PortableTransferServerTest {
    private val valid = "{\"version\":1}"
    private fun send(url: String, method: String = "GET", body: ByteArray = byteArrayOf(), headers: String = "", path: String? = null): String {
        val u = URI(url)
        return Socket(u.host, u.port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().apply {
                write(("$method ${path ?: u.path} HTTP/1.1\r\nHost: ${u.host}:${u.port}\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n$headers\r\n").toByteArray())
                write(body); flush()
            }
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
    @Test fun downloadIsOneShotAndImportIsValidatedBeforeCallback() {
        val imported = AtomicReference<String>()
        val done = CountDownLatch(1)
        val server = PortableTransferServer("127.0.0.1", valid, { require(it == valid) }, { imported.set(it); done.countDown() })
        try {
            assertTrue(send(server.url).contains("备份与恢复"))
            assertTrue(send(server.url + "/download").contains(valid))
            assertTrue(send(server.url + "/download").startsWith("HTTP/1.1 410"))
            assertTrue(send(server.url + "/import", "POST", "{}".toByteArray()).startsWith("HTTP/1.1 400"))
            assertNull(imported.get())
            assertTrue(send(server.url + "/import", "POST", valid.toByteArray()).startsWith("HTTP/1.1 200"))
            assertTrue(done.await(1, TimeUnit.SECONDS)); assertEquals(valid, imported.get())
        } finally { server.close() }
        assertTrue(runCatching { send(server.url) }.isFailure)
    }
    @Test fun nonceOriginUtf8AndOversizeAreRejectedWithoutImport() {
        val server = PortableTransferServer("127.0.0.1", valid, {}, { fail("must not import") })
        try {
            assertTrue(send(server.url, path = "/guess").startsWith("HTTP/1.1 404"))
            assertTrue(send(server.url + "/import", "POST", valid.toByteArray(), "Origin: https://attacker.invalid\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(send(server.url + "/import", "POST", byteArrayOf(0xc3.toByte(), 0x28)).startsWith("HTTP/1.1 400"))
            assertTrue(send(server.url + "/import", "POST", valid.toByteArray(), "Transfer-Encoding: chunked\r\n").startsWith("HTTP/1.1 400"))
            val u = URI(server.url)
            Socket(u.host, u.port).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write("POST ${u.path}/import HTTP/1.1\r\nHost: ${u.host}:${u.port}\r\nContent-Type: application/json\r\nContent-Length: 8388609\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 413"))
            }
        } finally { server.close() }
    }
    @Test fun cancelInterruptsPartialUploadAndExpiryClosesListener() {
        val server = PortableTransferServer("127.0.0.1", valid, {}, { fail("cancelled import") })
        val u = URI(server.url)
        Socket(u.host, u.port).use { socket ->
            socket.soTimeout = 2000
            socket.getOutputStream().write("POST ${u.path}/import HTTP/1.1\r\nHost: ${u.host}:${u.port}\r\nContent-Type: application/json\r\nContent-Length: 20\r\n\r\n{".toByteArray())
            server.close()
            assertTrue(runCatching { socket.getInputStream().read() }.getOrDefault(-1) == -1)
        }
        assertTrue(runCatching { send(server.url) }.isFailure)
        val expired = CountDownLatch(1)
        val short = PortableTransferServer("127.0.0.1", valid, {}, {}, { expired.countDown() }, 150)
        try {
            assertTrue(expired.await(2, TimeUnit.SECONDS))
            assertTrue(runCatching { send(short.url) }.isFailure)
        } finally { short.close() }
    }
}
