// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import java.util.ArrayDeque
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.BufferedSource
import okio.buffer

/** Monotonic receive rate; contains byte counts only, never request addresses or credentials. */
class NetworkRateMeter(private val now:()->Long={System.nanoTime()/1_000_000}) {
    private data class Bucket(val at:Long,var bytes:Long)
    private val samples=ArrayDeque<Bucket>()
    private var started:Long?=null
    private var last=0L
    @Synchronized fun reset() {samples.clear();started=null;last=now()}
    @Synchronized fun record(bytes:Long) {
        if(bytes<=0)return
        val time=now()
        if(started==null || time<last) {samples.clear();started=time}
        last=time
        if(samples.lastOrNull()?.let {time-it.at<100}==true) samples.last.bytes+=bytes
        else samples.addLast(Bucket(time,bytes))
        prune(time)
    }
    private fun prune(time:Long) {while(samples.isNotEmpty() && time-samples.first.at>=5000)samples.removeFirst()}
    @Synchronized fun bytesPerSecond():Long {
        val time=now();val first=started ?: return 0
        if(time<last) {reset();return 0}
        last=time
        prune(time);if(samples.isEmpty())return 0
        val elapsed=(time-first).coerceIn(250,5000)
        return samples.sumOf {it.bytes}.coerceAtMost(Long.MAX_VALUE/1000)*1000/elapsed
    }
    fun label():String {
        val rate=bytesPerSecond()
        return if(rate>=1024*1024) String.format(java.util.Locale.ROOT,"%.1f MB/s",rate/1048576.0)
            else "${rate/1024} KB/s"
    }
}

class ReceiveRateInterceptor(private val meter:NetworkRateMeter):Interceptor {
    override fun intercept(chain:Interceptor.Chain):Response {
        val response=chain.proceed(chain.request());val original=response.body ?: return response
        val wrapped=object:ResponseBody() {
            private val measured:BufferedSource=object:ForwardingSource(original.source()) {
                override fun read(sink:okio.Buffer,byteCount:Long):Long=super.read(sink,byteCount).also { if(it>0)meter.record(it) }
            }.buffer()
            override fun contentType()=original.contentType()
            override fun contentLength()=original.contentLength()
            override fun source()=measured
        }
        return response.newBuilder().body(wrapped).build()
    }
}
