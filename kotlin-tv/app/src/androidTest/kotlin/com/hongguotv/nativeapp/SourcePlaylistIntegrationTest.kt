package com.hongguotv.nativeapp

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hongguotv.core.SleepTimer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Actual HTTP/Media3 playlist transitions: this is deliberately independent of official playback. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SourcePlaylistIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var activity: SourcePlaybackActivity? = null
    private var fixture: Fixture? = null
    private fun <T> ui(block: () -> T): T {
        val value = AtomicReference<T>(); val problem = AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { value.set(block()) } catch (error: Throwable) { problem.set(error) } }
        problem.get()?.let { throw it }; return value.get()
    }
    private fun field(name: String): Any? = SourcePlaybackActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun waitFor(timeout: Long = 15000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) { if (ui(condition)) return; Thread.sleep(50) }
        fail("Timed out waiting for source playlist transition")
    }
    private fun start(): ExoPlayer {
        val data = instrumentation.context.assets.open("manual-next-long.mp4").use { it.readBytes() }
        val server = Fixture(data).also { fixture = it }
        val context = instrumentation.targetContext
        context.getSharedPreferences("source-portable-progress-v1", 0).edit().clear().commit()
        val source = ThirdSource("playlist-fixture", "Fixture", "${server.base}/api")
        ThirdStore(context).mergeSources(listOf(source))
        val detail = ThirdDetail(ThirdVideo("playlist-fixture", "Playlist fixture"), "", listOf("Fixture" to (1..3).map { ThirdEpisode("$it", "${server.base}/$it.mp4") }))
        SourcePlaybackQueue.offer(source, detail, 0, 0)
        activity = instrumentation.startActivitySync(Intent(context, SourcePlaybackActivity::class.java).putExtra("url", "${server.base}/1.mp4")
            .putExtra("title", "Playlist fixture · 1").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SourcePlaybackActivity
        waitFor { (field("player") as? ExoPlayer)?.let { it.isPlaying && it.duration > 0 } == true }
        return ui { field("player") as ExoPlayer }
    }
    private fun change(direction: Int) = SourcePlaybackActivity::class.java.getDeclaredMethod("changeEpisode", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(activity, direction, true)
    @After fun close() {
        ui { activity?.finish() }; instrumentation.waitForIdleSync(); fixture?.close()
        ThirdStore(instrumentation.targetContext).removeSource("playlist-fixture")
    }
    @Test fun manualNextAndPreviousReuseOnePlayerAndNaturalEndContinues() {
        val p = start()
        ui { change(1) }; waitFor { field("episodeIndex") == 1 && p.isPlaying }
        assertSame(p, ui { field("player") })
        ui { change(-1) }; waitFor { field("episodeIndex") == 0 && p.isPlaying }
        assertSame(p, ui { field("player") })
        ui { p.seekTo((p.duration-200).coerceAtLeast(0)) }
        waitFor(10000) { field("episodeIndex") == 1 && p.isPlaying }
        assertSame(p, ui { field("player") }); assertNull(ui { p.playerError })
        android.util.Log.i("SourcePlaylistTest", "Real HTTP playlist next/previous/EOF reused one ExoPlayer")
    }
    @Test fun endOfEpisodeSleepHasPriorityOverNaturalAndManualNext() {
        val p = start()
        ui { (field("sleep") as SleepTimer).afterEpisodes(1); p.seekTo((p.duration-200).coerceAtLeast(0)) }
        waitFor(10000) { p.playbackState == Player.STATE_ENDED && !p.playWhenReady }
        assertEquals(0, ui { field("episodeIndex") })
        ui { change(1) }; instrumentation.waitForIdleSync()
        assertEquals(0, ui { field("episodeIndex") }); assertSame(p, ui { field("player") })
        android.util.Log.i("SourcePlaylistTest", "Source episode-end sleep prevented natural and manual next")
    }

    private class Fixture(private val bytes: ByteArray) {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newFixedThreadPool(3)
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val base = "http://127.0.0.1:${listener.localPort}"
        private val acceptor = Thread {
            while (!listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                socket.soTimeout = 5000; sockets += socket
                try { workers.execute { serve(socket) } } catch (_: java.util.concurrent.RejectedExecutionException) { socket.close() }
            }
        }.apply { isDaemon = true; start() }
        private fun serve(socket: Socket) {
            try { socket.use { client ->
                val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                val first = reader.readLine() ?: return
                val headers = StringBuilder(); var total = 0
                while (true) { val line = reader.readLine() ?: return; total += line.length; if (total > 8192) return; if (line.isEmpty()) break; headers.append(line).append('\n') }
                val range = Regex("(?im)^Range: bytes=(\\d+)-(\\d*)\\s*$").find(headers)
                val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                val output = client.getOutputStream()
                if (start !in bytes.indices || end < start) { output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return }
                val contentRange = if (range == null) "" else "Content-Range: bytes $start-$end/${bytes.size}\r\n"
                output.write("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n${contentRange}Content-Length: ${end-start+1}\r\nConnection: close\r\n\r\n".toByteArray())
                if (!first.startsWith("HEAD ")) output.write(bytes, start, end-start+1)
                output.flush()
            } } catch (_: java.io.IOException) { } finally { sockets -= socket; runCatching { socket.close() } }
        }
        fun close() { listener.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow(); acceptor.join(1000) }
    }
}
