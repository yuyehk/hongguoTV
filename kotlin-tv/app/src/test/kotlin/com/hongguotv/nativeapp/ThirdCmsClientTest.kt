// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ThirdCmsClientTest {
    @Test fun explicitSecondAggregatePageUsesProtocolPageAndTracksRemainingSources() {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")); server.soTimeout = 3000
        val executor = Executors.newSingleThreadExecutor(); val pageRequests = AtomicInteger()
        val serving = executor.submit {
            repeat(2) { server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader(); val line = reader.readLine()
                if (line.contains("pg=2")) pageRequests.incrementAndGet()
                while (!reader.readLine().isNullOrEmpty()) { }
                val body = if (line.contains("/a?")) """{"page":2,"pagecount":3,"list":[{"vod_id":"2","vod_name":"Page Two"}]}""" else """{"page":2,"pagecount":3,"list":[]}"""
                val bytes = body.toByteArray()
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
            } }
        }
        val client = ThirdCmsClient()
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            val result = client.searchAll(listOf(ThirdSource("a", "A", "$base/a"), ThirdSource("b", "B", "$base/b")), "Page", 2)
            serving.get(4, TimeUnit.SECONDS)
            assertEquals(2, pageRequests.get()); assertEquals(2, result.page)
            assertEquals(listOf("a"), result.nextSourceIds); assertTrue(result.failedSourceIds.isEmpty())
            assertEquals("2", result.groups.single().hits.single().video.id)
            assertTrue(result.measurements.values.all { it.success && it.elapsedMs > 0 })
        } finally { client.close(); server.close(); executor.shutdownNow() }
    }
    @Test fun cancellingAnInFlightSourceClosesTheResponseRead() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); server.soTimeout = 3000
        val executor = Executors.newFixedThreadPool(2); val entered = CountDownLatch(1)
        val serving = executor.submit { server.accept().use { socket ->
            socket.soTimeout = 3000; val reader = socket.getInputStream().bufferedReader()
            while (!reader.readLine().isNullOrEmpty()) { }
            entered.countDown()
            runCatching { socket.getInputStream().read() }
        } }
        val client = ThirdCmsClient()
        try {
            val response = executor.submit<Boolean> { runCatching { client.list(ThirdSource("a", "A", "http://127.0.0.1:${server.localPort}/api")) }.isFailure }
            assertTrue(entered.await(3, TimeUnit.SECONDS)); client.cancel()
            assertTrue(response.get(3, TimeUnit.SECONDS)); serving.get(3, TimeUnit.SECONDS)
        } finally { client.close(); server.close(); executor.shutdownNow() }
    }
    @Test fun interruptedRequestCannotContactAnOptionalSource() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 500
        val executor = Executors.newSingleThreadExecutor()
        val accepted = executor.submit<Boolean> {
            runCatching { server.accept().use { connection ->
                val body = "{\"list\":[]}".toByteArray()
                connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
                true
            } }.getOrDefault(false)
        }
        val client = ThirdCmsClient()
        try {
            Thread.currentThread().interrupt()
            try { client.list(ThirdSource("cancelled", "cancelled", "http://127.0.0.1:${server.localPort}/api")) }
            catch (_: Exception) { }
            finally { Thread.interrupted() }
            assertFalse("a cancelled request must not open an optional network connection", accepted.get())
        } finally { Thread.interrupted(); client.close(); server.close(); executor.shutdownNow() }
    }
    @Test fun explicitAggregateReadsAreBoundedToThreeAndKeepPartialSuccess() {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newCachedThreadPool()
        val active = AtomicInteger(); val maximum = AtomicInteger(); val leakedHeaders = AtomicInteger()
        executor.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                executor.execute {
                    socket.use { connection ->
                        connection.soTimeout = 3000
                        val reader = connection.getInputStream().bufferedReader()
                        val path = reader.readLine().substringAfter(' ').substringBefore(' ').substringBefore('?')
                        val headers = mutableListOf<String>()
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headers += line }
                        val simultaneous = active.incrementAndGet(); maximum.accumulateAndGet(simultaneous, ::maxOf)
                        try {
                            if (headers.any { it.startsWith("Authorization:", true) || it.startsWith("Cookie:", true) }) leakedHeaders.incrementAndGet()
                            Thread.sleep(100)
                            val failure = path == "/failed"
                            val body = if (failure) "{}" else """{"list":[{"vod_id":"$path","vod_name":"同名剧"}],"page":1,"pagecount":1}"""
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            val status = if (failure) "503 Service Unavailable" else "200 OK"
                            connection.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8) + bytes)
                        } finally { active.decrementAndGet() }
                    }
                }
            }
        }
        val client = ThirdCmsClient()
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            val sources = (listOf("a", "b", "c", "d", "failed")).map { ThirdSource(it, it, "$base/$it") }
            val result = client.searchAll(sources, "同名剧")
            assertEquals(4, result.successfulSources)
            assertEquals(5, result.totalSources)
            assertEquals(4, result.groups.single().hits.size)
            assertTrue("concurrency was ${maximum.get()}", maximum.get() in 2..3)
            assertEquals(0, leakedHeaders.get())
        } finally { client.close(); server.close(); executor.shutdownNow() }
    }
}
