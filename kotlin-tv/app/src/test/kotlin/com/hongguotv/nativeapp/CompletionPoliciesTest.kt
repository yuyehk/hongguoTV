package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class CompletionPoliciesTest {
    @Test fun receiveRateUsesSlidingTimeAndRejectsNegativeCounts() {
        var now=0L; val rate=NetworkRateMeter { now }
        assertEquals(0L,rate.bytesPerSecond())
        rate.record(1000); now=1000; assertEquals(1000L,rate.bytesPerSecond())
        rate.record(-999); now=6000; assertEquals(0L,rate.bytesPerSecond())
        now=1; rate.record(1000); now=1001; assertEquals(1000L,rate.bytesPerSecond())
    }
    @Test fun incompatibleSkipStopsAtFiveAndNeverSkipsNetworkOrSleep() {
        val budget=IncompatibleSkipBudget()
        repeat(5) { index-> assertEquals(index+1,budget.next(index,20,true,true,true)) }
        assertNull(budget.next(5,20,true,true,true))
        budget.rendered()
        assertNull(budget.next(0,20,true,false,true))
        assertNull(budget.next(0,20,true,true,false))
        assertNull(budget.next(0,20,false,true,true))
        assertNull(budget.next(20,20,true,true,true))
        assertEquals(1,budget.next(0,20,true,true,true))
        assertFalse(IncompatibleSkipBudget.codecError(2004))
        assertFalse(IncompatibleSkipBudget.codecError(6001))
        assertTrue(IncompatibleSkipBudget.codecError(4005))
    }
    @Test fun cacheClearOnlyTouchesArtworkAndNeverRecordsBackgroundOrKeys() {
        val root=Files.createTempDirectory("cache-policy").toFile()
        try {
            val artwork=File(root,"artwork-v1").apply {mkdirs()}; File(artwork,"cover").writeBytes(ByteArray(16))
            File(root,"native-library-v1.xml").writeText("keep records")
            File(root,"background.jpg").writeText("keep background")
            File(root,"account.jks").writeText("keep key")
            var cleared=false; val cache=SafeCacheManager(root) {cleared=true}
            assertEquals(16L,cache.bytes())
            assertEquals(16L,cache.clear().removedBytes)
            assertTrue(cleared)
            assertEquals("keep records",File(root,"native-library-v1.xml").readText())
            assertTrue(File(root,"background.jpg").isFile); assertTrue(File(root,"account.jks").isFile)
        } finally {
            check(root.name.startsWith("cache-policy") && root.canonicalFile.parentFile==File(System.getProperty("java.io.tmpdir")).canonicalFile)
            root.deleteRecursively()
        }
    }
    @Test fun exitRecommendationsPreferUpdatesAndDeduplicate() {
        val a=com.hongguotv.core.Series("1","更新"); val b=com.hongguotv.core.Series("2","推荐")
        val rows=ExitRecommendationPolicy.pick(listOf(a),listOf(a,b,b),emptySet())
        assertEquals(listOf("1","2"),rows.map {it.id})
        assertEquals(listOf("2"),ExitRecommendationPolicy.pick(listOf(a),listOf(b),setOf("1")).map {it.id})
    }
}
