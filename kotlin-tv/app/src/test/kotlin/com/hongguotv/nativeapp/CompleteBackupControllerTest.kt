package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import com.hongguotv.core.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CompleteBackupControllerTest {
    private fun data(id: String = "2", at: Long = 1) = BackupData(listOf(Series(id, "Imported")),
        listOf(BackupProgress(Series("1", "Series"), "101", 0, at, 10000, false, at)), emptySet(), emptyList(), BackupSettings(720, 1.5f))
    private fun loaded(activity: Activity): Library = Library(activity).also {
        Library::class.java.getDeclaredField("index").apply { isAccessible = true }
            .set(it, LibraryIndex(data("1", 9000).copy(settings = BackupSettings())))
    }
    private fun flush(controller: CompleteBackupController) {
        val worker = controller.javaClass.getDeclaredField("worker").apply { isAccessible = true }.get(controller) as ExecutorService
        repeat(3) { worker.submit {}.get(5, TimeUnit.SECONDS); ShadowLooper.idleMainLooper() }
    }
    private fun checkbox(v: View): CheckBox? = if (v is CheckBox) v else if (v is ViewGroup)
        (0 until v.childCount).firstNotNullOfOrNull { checkbox(v.getChildAt(it)) } else null
    @Test fun defaultPreviewKeepsGlobalOptionsAndNewerLocalProgress() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val a = activity.get(); val library = loaded(a); val preferences = FeaturePreferences(a)
        val appearance = AppearanceController(a, {}, {})
        var restored = 0
        val complete = CompleteBackupController(a, library, preferences, appearance) { restored++ }
        try {
            complete.preview(LocalFeatureBackup(data(), JSONObject().put("version", 1).put("seekStep", 30).put("theme", "LIGHT").toString(), 1,
                appearance = JSONObject().put("version", 1).put("theme", "LIGHT").put("image", "").toString()))
            flush(complete)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertFalse(checkbox(dialog.window!!.decorView)!!.isChecked)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            flush(complete)
            assertEquals(1, restored)
            assertEquals(setOf("1", "2"), library.favorites().map { it.id }.toSet())
            assertEquals(9000, library.progress("1")!!.position)
            assertEquals(1f, library.playbackSpeed)
            assertEquals(10, preferences.seekStepSeconds)
            assertEquals(FeatureTheme.DARK, preferences.theme)
        } finally { complete.destroy(); appearance.destroy(); library.close(); activity.pause().stop().destroy() }
    }
    @Test fun checkedOptionsRestorePlaybackAppearanceAndSocial() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val a = activity.get(); val library = loaded(a); val preferences = FeaturePreferences(a)
        val appearance = AppearanceController(a, {}, {})
        val complete = CompleteBackupController(a, library, preferences, appearance) {}
        try {
            val social = SocialPreferences(a); social.opacity = 37; social.enabled = true
            val raw = JSONObject(appearance.exportState()).put("theme", "LIGHT").toString()
            social.opacity = 100; social.enabled = false
            complete.preview(LocalFeatureBackup(data(), JSONObject().put("version", 1).put("seekStep", 30).toString(), 1, appearance = raw))
            flush(complete)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            checkbox(dialog.window!!.decorView)!!.isChecked = true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            flush(complete)
            assertEquals(1.5f, library.playbackSpeed); assertEquals(30, preferences.seekStepSeconds)
            assertEquals(FeatureTheme.LIGHT, preferences.theme)
            assertEquals(37, social.opacity); assertTrue(social.enabled)
        } finally { complete.destroy(); appearance.destroy(); library.close(); activity.pause().stop().destroy() }
    }
    @Test fun invalidFinalPartitionLeavesLibraryToolsAndSourceStateUnchanged() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val a = activity.get(); val library = loaded(a); val preferences = FeaturePreferences(a)
        val appearance = AppearanceController(a, {}, {})
        val complete = CompleteBackupController(a, library, preferences, appearance) {}
        try {
            preferences.addGroup("keep")
            val source = SourceLibraryBackup(a).exportState()
            complete.preview(LocalFeatureBackup(data(), JSONObject().put("version", 1).put("groups", org.json.JSONArray(listOf("unwanted"))).toString(), 1,
                source, "{\"version\":99}"))
            flush(complete)
            assertEquals(listOf("1"), library.favorites().map { it.id })
            assertEquals(listOf("keep"), preferences.groups())
            assertEquals(source, SourceLibraryBackup(a).exportState())
            assertTrue(ShadowDialog.getLatestDialog().isShowing)
        } finally { complete.destroy(); appearance.destroy(); library.close(); activity.pause().stop().destroy() }
    }
    @Test fun sourceCapacityFailureIsCheckedAgainBeforeAnyPartitionIsApplied() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val a = activity.get(); val library = loaded(a); val preferences = FeaturePreferences(a)
        val appearance = AppearanceController(a, {}, {})
        val complete = CompleteBackupController(a, library, preferences, appearance) {}
        try {
            val store = ThirdStore(a)
            store.mergeSources(listOf(ThirdSource("incoming", "Incoming", "https://incoming.example/api")))
            val source = SourceLibraryBackup(a).exportState()
            store.removeSource("incoming")
            complete.preview(LocalFeatureBackup(data(), JSONObject().put("version", 1).put("seekStep", 30).toString(), 1, source))
            flush(complete)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            store.mergeSources((1..30).map { ThirdSource("local$it", "Local", "https://local$it.example/api") })
            checkbox(dialog.window!!.decorView)!!.isChecked = true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            flush(complete)
            assertEquals(listOf("1"), library.favorites().map { it.id })
            assertEquals(10, preferences.seekStepSeconds)
            assertEquals(30, store.sources().size)
        } finally { complete.destroy(); appearance.destroy(); library.close(); activity.pause().stop().destroy() }
    }
    @Test fun cancelledPreviewCannotMergeRecords() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val a = activity.get(); val library = loaded(a); val appearance = AppearanceController(a, {}, {})
        val complete = CompleteBackupController(a, library, FeaturePreferences(a), appearance) { fail("cancelled") }
        try {
            complete.preview(LocalFeatureBackup(data(), "{\"version\":1}", 1))
            complete.close()
            flush(complete)
            assertEquals(listOf("1"), library.favorites().map { it.id })
        } finally { complete.destroy(); appearance.destroy(); library.close(); activity.pause().stop().destroy() }
    }
    @Test fun confirmedCommitFinishesInPersistentLibraryAfterActivityControllerIsDestroyed() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val a = activity.get(); val library = loaded(a); val preferences = FeaturePreferences(a)
        lateinit var complete: CompleteBackupController
        lateinit var appearance: AppearanceController
        var armed = false
        var oldDestroyed = false
        appearance = AppearanceController(a, {
            if (armed) { complete.destroy(); appearance.destroy(); library.close(); oldDestroyed = true }
        }, {})
        complete = CompleteBackupController(a, library, preferences, appearance) { fail("destroyed UI callback") }
        val recovered = Library(a.applicationContext)
        try {
            complete.preview(LocalFeatureBackup(data(), "{\"version\":1,\"seekStep\":30}", 1,
                appearance = "{\"version\":1,\"theme\":\"LIGHT\",\"image\":\"\"}"))
            flush(complete)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            checkbox(dialog.window!!.decorView)!!.isChecked = true
            armed = true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (System.nanoTime() < until) {
                ShadowLooper.idleMainLooper()
                val raw = a.getSharedPreferences("native-library-v1", 0).getString("favorites", "").orEmpty()
                if (oldDestroyed && raw.contains("Imported")) break
                Thread.sleep(10)
            }
            assertEquals(30, preferences.seekStepSeconds)
            assertTrue(oldDestroyed)
            assertEquals(FeatureTheme.LIGHT, preferences.theme)
            recovered.loadAsync { }
            while (!recovered.isLoaded && System.nanoTime() < until) { ShadowLooper.idleMainLooper(); Thread.sleep(10) }
            assertTrue(recovered.isLoaded)
            assertTrue(recovered.favorites().any { it.id == "2" })
        } finally { recovered.close(); complete.destroy(); appearance.destroy(); library.close(); activity.pause().stop().destroy() }
    }
    @Test fun immediateReopenSeesMergedFavoritesAndCannotEraseThemWithItsNextWrite() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val a = activity.get(); val library = loaded(a); val preferences = FeaturePreferences(a)
        library.restore(library.snapshot(), false)
        library.flush()
        lateinit var complete: CompleteBackupController
        lateinit var appearance: AppearanceController
        var reopened: Library? = null
        var armed = false
        appearance = AppearanceController(a, {
            if (armed && reopened == null) {
                complete.destroy(); appearance.destroy(); library.close()
                reopened = Library(a.applicationContext).also { fresh ->
                    // Reopen immediately: no polling/waiting for an Imported disk marker first.
                    fresh.loadAsync { }
                    val loader = Library::class.java.getDeclaredField("loader").apply { isAccessible = true }.get(fresh) as ExecutorService
                    loader.submit {}.get(5, TimeUnit.SECONDS)
                }
            }
        }, {})
        complete = CompleteBackupController(a, library, preferences, appearance) { fail("destroyed UI callback") }
        val reload = Library(a.applicationContext)
        try {
            complete.preview(LocalFeatureBackup(data(), "{\"version\":1}", 1,
                appearance = "{\"version\":1,\"theme\":\"LIGHT\",\"image\":\"\"}"))
            flush(complete)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            checkbox(dialog.window!!.decorView)!!.isChecked = true
            armed = true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (reopened?.isLoaded != true && System.nanoTime() < until) { ShadowLooper.idleMainLooper(); Thread.sleep(10) }
            val current = reopened!!
            assertTrue(current.isLoaded)
            assertEquals(setOf("1", "2"), current.favorites().map { it.id }.toSet())
            current.toggle(Series("3", "Reopened addition"))
            current.save(BackupProgress(Series("1", "Series"), "101", 0, 9500, 10000, false, 20000))
            current.flush(); current.close()
            reload.loadAsync { }
            while (!reload.isLoaded && System.nanoTime() < until) { ShadowLooper.idleMainLooper(); Thread.sleep(10) }
            assertTrue(reload.isLoaded)
            assertEquals(setOf("1", "2", "3"), reload.favorites().map { it.id }.toSet())
            assertEquals(9500, reload.progress("1")!!.position)
        } finally { reopened?.close(); reload.close(); complete.destroy(); appearance.destroy(); library.close(); activity.pause().stop().destroy() }
    }
}
