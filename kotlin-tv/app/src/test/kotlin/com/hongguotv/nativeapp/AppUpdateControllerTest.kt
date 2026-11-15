package com.hongguotv.nativeapp

import android.app.Activity
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AppUpdateControllerTest {
    @Test fun openingChecksQuietlyAndPromptsOnlyForANewerVersion() {
        val current = BuildConfig.VERSION_NAME.substringBefore('-')
        withUpdater(reply = { checksum(current) }) { updater, finish ->
            updater.checkOnOpen { true }
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
            finish()
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
        }
        withUpdater(reply = { checksum("999.0.0") }) { updater, finish ->
            updater.checkOnOpen { true }
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
            finish()
            val prompt = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(prompt.isShowing)
            assertEquals("下载并安装", prompt.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text.toString())
        }
    }

    @Test fun openingDoesNotInterruptPlaybackOrSurfaceANetworkFailure() {
        withUpdater(reply = { throw SocketTimeoutException() }) { updater, finish ->
            updater.checkOnOpen { true }
            finish()
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
        }
        withUpdater(reply = { checksum("999.0.0") }) { updater, finish ->
            var canPrompt = true
            updater.checkOnOpen { canPrompt }
            canPrompt = false
            finish()
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
            canPrompt = true
            updater.showPendingUpdate { canPrompt }
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        }
    }

    @Test fun transientCheckFailureRetriesThenRecordsTheSuccessfulResult() {
        var requests = 0
        val current = BuildConfig.VERSION_NAME.substringBefore('-')
        withUpdater(reply = {
            if (++requests == 1) throw SocketTimeoutException()
            checksum(current)
        }) { updater, finish ->
            updater.checkOnOpen { true }
            finish()
            assertEquals(2, requests)
            assertTrue(updater.diagnostics().contains("已是最新"))
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
        }
    }

    @Test fun invalidReleaseMetadataIsNotRetried() {
        var requests = 0
        withUpdater(reply = { requests++; "invalid checksum file" }) { updater, finish ->
            updater.checkOnOpen { true }
            finish()
            assertEquals(1, requests)
            assertTrue(updater.diagnostics().contains("失败"))
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
        }
    }

    private fun withUpdater(
        reply: () -> String,
        verify: (AppUpdateController, () -> Unit) -> Unit,
    ) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val worker = Executors.newSingleThreadExecutor()
        val source = AppUpdateSource(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(reply().toResponseBody()).build()
        }.build())
        val updater = AppUpdateController(activity.get(), source, worker)
        try {
            verify(updater) {
                worker.submit {}.get(5, TimeUnit.SECONDS)
                ShadowLooper.idleMainLooper()
            }
        } finally {
            updater.destroy()
            activity.pause().stop().destroy()
        }
    }

    private fun checksum(version: String) =
        "${"0".repeat(64)}  hongguotv-$version-android14-transitions.apk"
}
