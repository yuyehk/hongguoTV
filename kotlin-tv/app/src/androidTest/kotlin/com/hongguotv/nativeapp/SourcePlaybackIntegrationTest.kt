package com.hongguotv.nativeapp

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real HTTP and Media3 playback, with only the bytes supplied by a bounded loopback fixture. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SourcePlaybackIntegrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private var activity: SourcePlaybackActivity?=null
    private var server: Mp4FixtureServer?=null
    private fun <T> ui(action: ()->T): T {
        val result=AtomicReference<T>();val failure=AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try {result.set(action())} catch(t:Throwable) {failure.set(t)} }
        failure.get()?.let {throw it};return result.get()
    }
    private fun field(name:String):Any?=SourcePlaybackActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.get(activity)
    private fun waitFor(timeout:Long=15000,condition:()->Boolean) {
        val deadline=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<deadline) {if(ui(condition)) return;Thread.sleep(50)}
        fail("Timed out waiting for the source player lifecycle")
    }
    private fun start(longClip:Boolean=false):ExoPlayer {
        val policy=android.security.NetworkSecurityPolicy.getInstance()
        assertTrue("Explicit user HTTP/LAN sources must be usable",policy.isCleartextTrafficPermitted("127.0.0.1"))
        listOf("fqnovel.com","reading.snssdk.com","www.hongguoduanju.com").forEach { host ->
            assertFalse("Official account/content hosts must retain HTTPS policy",policy.isCleartextTrafficPermitted(host))
        }
        val bytes=instrumentation.context.assets.open(if(longClip) "manual-next-long.mp4" else "autoplay-test.mp4").use {it.readBytes()}
        val fixture=Mp4FixtureServer(bytes).also {server=it}
        val key="position-"+SourceMediaPolicy.key(fixture.url)
        instrumentation.targetContext.getSharedPreferences("native-source-progress-v1",0).edit().remove(key).commit()
        activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,SourcePlaybackActivity::class.java)
            .putExtra("url",fixture.url).putExtra("title","Local source fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SourcePlaybackActivity
        waitFor { (field("player") as? ExoPlayer)?.let {it.isPlaying && it.duration>0}==true }
        return ui {field("player") as ExoPlayer}
    }
    private fun stored():Long {
        val key="position-"+SourceMediaPolicy.key(server!!.url)
        return instrumentation.targetContext.getSharedPreferences("native-source-progress-v1",0).getLong(key,-1)
    }
    @After fun close() {
        ui {activity?.finish()};instrumentation.waitForIdleSync()
        server?.close()
    }
    @Test fun completedSourceKeepsZeroResumeAcrossRepeatedLifecycleCheckpoints() {
        val player=start()
        assertEquals(C.USAGE_MEDIA,ui {player.audioAttributes.usage})
        assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE,ui {player.audioAttributes.contentType})
        assertTrue(server!!.requests.get()>0)
        ui {player.seekTo((player.duration-200).coerceAtLeast(0))}
        waitFor(6000) {player.playbackState==Player.STATE_ENDED}
        repeat(3) {
            ui {
                SourcePlaybackActivity::class.java.getDeclaredMethod("save",Boolean::class.javaPrimitiveType).apply {isAccessible=true}.invoke(activity,false)
                val state=Bundle();instrumentation.callActivityOnSaveInstanceState(activity!!,state)
                assertEquals(0L,state.getLong("position"))
            }
            assertEquals("EOF must remain restart-at-zero after later saves",0L,stored())
        }
        ui {activity!!.finish()};instrumentation.waitForIdleSync()
        assertEquals(0L,stored())
        android.util.Log.i("SourcePlaybackTest","Real HTTP source uses MEDIA/MOVIE audio; EOF resume stayed zero across lifecycle saves")
    }
    @Test fun replayAfterEofStartsAgainAtTheBeginning() {
        val player=start()
        ui {player.seekTo((player.duration-200).coerceAtLeast(0))}
        waitFor(6000) {player.playbackState==Player.STATE_ENDED}
        assertEquals(0L,stored())
        ui {SourcePlaybackActivity::class.java.getDeclaredMethod("requestSourcePlay").apply {isAccessible=true}.invoke(activity)}
        waitFor(6000) {player.isPlaying && player.currentPosition in 0..2000}
        assertTrue(ui {player.playWhenReady})
        assertNull(ui {player.playerError})
        android.util.Log.i("SourcePlaybackTest","Source menu/media play path restarted EOF at the beginning")
    }

    @Test fun sourceBackgroundReleaseReturnsPausedAtSavedPosition() {
        val player=start(longClip=true)
        ui {player.seekTo(3500)}
        waitFor {player.isPlaying && player.currentPosition>=3500}
        ui {activity!!.moveTaskToBack(true)}
        waitFor {field("player")==null}
        val saved=stored()
        assertTrue(saved in 3000..10000)
        // Reorder the same stopped activity to the foreground; its onResume must not auto-play.
        ui {instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext,SourcePlaybackActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))}
        waitFor { (field("player") as? ExoPlayer)?.playbackState==Player.STATE_READY }
        val resumed=ui {field("player") as ExoPlayer}
        assertFalse(ui {resumed.playWhenReady})
        assertFalse(ui {resumed.isPlaying})
        assertTrue("Background return should retain the saved position",ui {kotlin.math.abs(resumed.currentPosition-saved)<700})
        assertNull(ui {resumed.playerError})
        android.util.Log.i("SourcePlaybackTest","Source background return was paused at saved position ${saved}ms")
    }

    /** Limits clients, headers, worker concurrency and read time; binds only the emulator loopback. */
    private class Mp4FixtureServer(private val bytes:ByteArray):Closeable {
        private val listener=ServerSocket(0,8,InetAddress.getByName("127.0.0.1")).apply {soTimeout=500}
        private val workers=Executors.newFixedThreadPool(2)
        private val sockets=ConcurrentHashMap.newKeySet<Socket>()
        @Volatile private var closed=false
        val requests=AtomicInteger()
        val url="http://127.0.0.1:${listener.localPort}/video.mp4"
        private val acceptor=Thread({
            while(!closed && requests.get()<64) {
                try {
                    val socket=listener.accept().apply {soTimeout=5000};sockets+=socket
                    requests.incrementAndGet()
                    try {workers.execute {serve(socket)}} catch(_:java.util.concurrent.RejectedExecutionException) {sockets-=socket;socket.close()}
                } catch(_:SocketTimeoutException) {} catch(_:java.io.IOException) {if(!closed) closed=true}
            }
        },"source-fixture-accept").apply {isDaemon=true;start()}
        private fun serve(socket:Socket) {
            try {socket.use {
                val input=it.getInputStream();val header=java.io.ByteArrayOutputStream()
                while(header.size()<16*1024) {
                    val b=input.read();if(b<0) return
                    header.write(b)
                    val a=header.toByteArray();val n=a.size
                    if(n>=4 && a[n-4]==13.toByte() && a[n-3]==10.toByte() && a[n-2]==13.toByte() && a[n-1]==10.toByte()) break
                }
                val raw=header.toString("US-ASCII")
                val range=Regex("(?im)^Range: bytes=(\\d+)-(\\d*)\\s*$").find(raw)
                val begin=range?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val last=range?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(bytes.lastIndex.toLong()) ?: bytes.lastIndex.toLong()
                val output=it.getOutputStream()
                if(begin !in 0..bytes.lastIndex.toLong() || last<begin) {
                    output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${bytes.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());return
                }
                val length=last-begin+1
                val status=if(range==null) "200 OK" else "206 Partial Content"
                val contentRange=if(range==null) "" else "Content-Range: bytes $begin-$last/${bytes.size}\r\n"
                output.write("HTTP/1.1 $status\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n${contentRange}Content-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
                if(!raw.startsWith("HEAD ")) {
                    var cursor=begin.toInt();val end=last.toInt()+1
                    while(cursor<end) {val count=minOf(64*1024,end-cursor);output.write(bytes,cursor,count);cursor+=count}
                }
                output.flush()
            }} catch(_:java.io.IOException) { /* Client cancellation is normal on pause/seek/release. */ }
            finally {sockets-=socket;runCatching {socket.close()}}
        }
        override fun close() {
            closed=true;runCatching {listener.close()};sockets.forEach {runCatching {it.close()}}
            workers.shutdownNow();acceptor.join(1000);workers.awaitTermination(2,TimeUnit.SECONDS)
        }
    }
}
