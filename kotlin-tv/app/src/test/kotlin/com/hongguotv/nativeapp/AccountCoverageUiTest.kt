package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import com.hongguotv.core.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AccountCoverageUiTest {
    private fun field(account: AccountController, name: String) = account.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun flush(account: AccountController) {
        (field(account, "worker").get(account) as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper()
    }
    private fun fixture(account: AccountController) {
        field(account, "credentials").set(account, AccountCredentials("test-fixture-only"))
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val text = when {
                chain.request().url.encodedPath.contains("user/info") -> """{"code":0,"data":{"biz_user_id":"fixture-user","user_name":"fixture"}}"""
                chain.request().url.encodedPath.contains("read_progress") -> """{"code":0,"video_data":[],"has_more":true}"""
                chain.request().url.encodedPath.contains("subscribe/list") -> """{"code":0,"data":{"subscribe_items":[]}}"""
                else -> """{"code":0,"data":{"video_shelf_info":[]}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(text.toResponseBody()).build()
        }.build()
        field(account, "client").set(account, CloudAccountClient(http))
    }

    @Test fun successfulReadsRetainRecentAndPartialCoverageInVisibleStates() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val account = AccountController(activity.get(), {}, AccountStore(activity.get(), FixtureAccountCipher()), offlineAccountHttp())
        try {
            flush(account)
            fixture(account)
            account.refreshHistory()
            flush(account)
            assertTrue(account.historyStatus.contains("最近50"))
            assertTrue(account.historyStatus.contains("仍有后续"))
            account.refreshFavorites()
            flush(account)
            assertTrue(account.favoriteState().message.contains("分页协议未验证"))
            assertFalse(account.favoriteState().coverage!!.complete)
            account.refreshReservations()
            flush(account)
            for (online in listOf(false, true)) {
                assertTrue(account.reservationState(online).message.contains("首批20"))
                assertFalse(account.reservationState(online).coverage!!.paginationSupported)
            }
        } finally { account.destroy(); activity.pause().stop().destroy() }
    }

    @Test fun syncStatusExplainsLimitsWithoutAFalseLoadMoreButton() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val account = AccountController(activity.get(), {}, AccountStore(activity.get(), FixtureAccountCipher()), offlineAccountHttp())
        try {
            flush(account)
            fixture(account)
            account.show()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            ShadowLooper.idleMainLooper()
            val message = shadowOf(ShadowAlertDialog.getLatestAlertDialog()).message.toString()
            assertTrue(message.contains("最近50"))
            assertTrue(message.contains("首批20"))
            assertTrue(message.contains("不能保证完整同步"))
        } finally { account.destroy(); activity.pause().stop().destroy() }
    }
}
