package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class SourceImportServerTest {
    @Test fun importRequiresNonceAndBoundedBodyAndStopsAfterClose() {
        val count = AtomicInteger()
        val server = SourceImportServer { raw -> count.incrementAndGet(); ThirdCms.parseSources(raw).size }
        fun post(path: String, body: String): Int {
            val connection = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
            connection.connectTimeout = 2000; connection.readTimeout = 2000; connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            return connection.responseCode.also { connection.disconnect() }
        }
        try {
            assertEquals(404, post("/wrong/import", "{\"sites\":[]}"))
            assertEquals(0, count.get())
            assertEquals(200, post("/${server.nonce}/import", "{\"sites\":[]}"))
            assertEquals(1, count.get())
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write("POST /${server.nonce}/import HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: ${ThirdCms.MAX_BYTES+1}\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readLine().contains("413"))
            }
            assertEquals(1, count.get())
        } finally { server.close() }
        assertTrue(runCatching { URL("http://127.0.0.1:${server.port}/${server.nonce}").openStream().close() }.isFailure)
    }
}
