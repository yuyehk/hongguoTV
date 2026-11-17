package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class MainAppUpdateIntegrationTest {
    @Test fun toolsExposeAppUpdateAndItsLastCheckResult() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val tools = MainActivity::class.java.getDeclaredField("featureTools").apply {
                isAccessible = true
            }.get(activity) as FeatureTools
            tools.show()
            val menu = ShadowAlertDialog.getLatestAlertDialog().listView
            val entries = (0 until menu.count).map { menu.adapter.getItem(it).toString() }
            assertEquals(1, entries.count { it == "检查应用更新" })
            val history = entries.indexOf("更新检查记录")
            check(history >= 0) { "Missing update check history" }
            menu.performItemClick(null, history, menu.adapter.getItemId(history))
            assertEquals(
                "当前版本 ${BuildConfig.VERSION_NAME}\n尚无更新检查结果。",
                org.robolectric.Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).message.toString(),
            )
        } finally {
            controller.destroy()
        }
    }

    @Test fun appRegistersItsUpdateApkFileProvider() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val provider = activity.packageManager.resolveContentProvider("${activity.packageName}.app-updates", 0)
            assertNotNull("Missing provider for handing update APKs to the system installer", provider)
        } finally {
            controller.destroy()
        }
    }
}
