package com.hongguotv.nativeapp

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class FeatureEntryIntegrationTest {
    private fun get(target: Any, name: String): Any = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)!!
    private fun texts(view: View): List<TextView> = if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else if (view is TextView) listOf(view) else emptyList()

    @Test fun toolsAreReachableFromHomeWithoutRestoringSettingsOrUpdater() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val library = get(activity, "library") as Library
            (get(library, "loader") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            (get(get(activity, "cloudAccount"), "worker") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            MainActivity::class.java.getDeclaredMethod("showCatalog", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, false, false, false)
            val rows = texts(activity.findViewById(android.R.id.content))
            val tools = rows.firstOrNull { it.text == "工具" }
            assertNotNull("The optional tools must have a D-pad reachable entry", tools)
            assertTrue(tools!!.isFocusable)
            assertFalse(rows.any { it.text == "设置" || it.text.toString().contains("版本与更新") })
            tools.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog)
            assertTrue(dialog.isShowing)
        } finally { controller.destroy() }
    }
}
