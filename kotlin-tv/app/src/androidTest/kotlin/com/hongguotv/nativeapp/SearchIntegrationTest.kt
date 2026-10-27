package com.hongguotv.nativeapp

import android.content.Intent
import android.graphics.Bitmap
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hongguotv.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Live public search and media; no repository or player replacement. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SearchIntegrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private var activity: MainActivity?=null
    private fun <T> ui(action: ()->T): T {
        val result=AtomicReference<T>(); val failure=AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { result.set(action()) } catch(t: Throwable) { failure.set(t) } }
        failure.get()?.let { throw it }; return result.get()
    }
    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
    private fun waitFor(timeout: Long=45000,condition: ()->Boolean) {
        val deadline=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<deadline) { if(ui(condition)) return; Thread.sleep(100) }
        fail("Timed out waiting for live search/playback")
    }
    @Suppress("UNCHECKED_CAST") private fun items()=field("catalog") as List<Series>
    private fun search(keyword: String,type: ContentType) {
        ui { MainActivity::class.java.getDeclaredMethod("runSearch",String::class.java,ContentType::class.java)
            .apply { isAccessible=true }.invoke(activity,keyword,type) }
        waitFor { items().isNotEmpty() }
        assertEquals(type,ui { (field("library") as Library).contentType })
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null),name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
    }
    @After fun close() { ui { activity?.finish() }; instrumentation.waitForIdleSync() }

    @Test fun liveCombinedSearchAppendsSecondPageThenPlaysAResultOnApi34() {
        activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        waitFor { (field("library") as Library).isLoaded }
        search("修仙",ContentType.COMIC)
        val first=ui { items().map { it.id }.toSet() }
        val selected=ui { items().first() }
        val firstSize=ui { items().size }
        capture("search-comic-page1.png")
        ui { (field("continuousCatalog") as ContinuousCatalogView).next.performClick() }
        waitFor { field("page")==2 && items().size>firstSize }
        assertEquals(2,ui { field("page") })
        val second=ui { items().drop(firstSize).map { it.id }.toSet() }
        assertTrue("The second page must add results",second.isNotEmpty())
        assertTrue("Appended page2 must not repeat page1",first.intersect(second).isEmpty())
        capture("search-comic-page2.png")
        search("总裁",ContentType.SHORT)
        val shorts=ui { items().size }; capture("search-short.png")
        ui { MainActivity::class.java.getDeclaredMethod("openDetail",Series::class.java,
            Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,CloudProgress::class.java,Boolean::class.javaPrimitiveType).apply { isAccessible=true }
            .invoke(activity,selected,true,true,null,false) }
        waitFor(60000) { (field("player") as? ExoPlayer)?.isPlaying==true }
        assertFalse(ui { field("playError") as Boolean })
        Thread.sleep(1500); capture("search-result-playing.png")
        android.util.Log.i("SearchTest","Live search: comicPage1=${first.size} comicPage2=${second.size} overlap=0 shortPage1=$shorts resultPlaying=true api=${android.os.Build.VERSION.SDK_INT}")
    }
}
