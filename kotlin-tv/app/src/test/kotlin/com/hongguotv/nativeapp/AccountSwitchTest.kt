package com.hongguotv.nativeapp
import android.app.Activity
import com.hongguotv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.*
import org.json.JSONObject
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34]) @LooperMode(LooperMode.Mode.PAUSED)
class AccountSwitchTest {
    private fun credential(id:String)=AccountCredentials("sessionid=fixture-$id","d-$id","i-$id")
    private fun fixture(http:OkHttpClient):Triple<org.robolectric.android.controller.ActivityController<Activity>,AccountController,AccountStore>{
        val a=Robolectric.buildActivity(Activity::class.java).setup();a.get().getSharedPreferences("cloud_account_secure",0).edit().clear().commit()
        val s=AccountStore(a.get(),FixtureAccountCipher());s.save(credential("b"),CloudProfile("b","乙"));s.save(credential("a"),CloudProfile("a","甲","https://example.org/a.png"))
        return Triple(a,AccountController(a.get(),{},s,http),s)
    }
    private fun http(block:(Request)->String)=OkHttpClient.Builder().addInterceptor {chain->Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(block(chain.request()).toResponseBody()).build()}.build()
    private fun body(r:Request):String {val id=if(r.header("Cookie").orEmpty().endsWith("-b"))"b" else "a";return when {
        r.url.encodedPath.contains("user/info")->"{\"code\":0,\"data\":{\"biz_user_id\":\"$id\",\"user_name\":\"${if(id=="a")"甲" else "乙"}\",\"avatar_url\":\"https://example.org/$id.png\"}}"
        r.url.encodedPath.contains("read_progress/list")->"{\"code\":0,\"video_data\":[]}"
        r.url.encodedPath.contains("bookshelf/video/info")->"{\"code\":0,\"data\":{\"video_shelf_info\":[]}}"
        r.url.encodedPath.contains("subscribe/list")->"{\"code\":0,\"data\":{\"subscribe_items\":[]}}"
        else->"{\"code\":0}"
    }}
    private fun drain(c:AccountController){repeat(5){(c.javaClass.getDeclaredField("worker").apply{isAccessible=true}.get(c) as ExecutorService).submit{}.get(8,TimeUnit.SECONDS);ShadowLooper.idleMainLooper()}}
    @Test fun accountMenuRetainsAllActionsAndCanOpenDuringRefresh(){
        val(a,c,_)=fixture(http(::body));try{drain(c)
            c.javaClass.getDeclaredField("busy").apply{isAccessible=true}.set(c,true);c.show()
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog)
            val labels=(0 until dialog.listView.adapter.count).map{dialog.listView.adapter.getItem(it).toString()}
            assertTrue(labels.any{it.contains("云端继续观看")});assertTrue(labels.contains("退出当前红果账号"));assertTrue(labels.contains("切换已保存账号"));assertTrue(labels.contains("管理已保存账号"))
        }finally{c.destroy();a.pause().stop().destroy()}
    }
    @Test fun switchIsDurableClearsCloudScopeAndLeavesNativeData(){
        val (a,c,s)=fixture(http(::body));try {drain(c);assertEquals("甲",c.accountLabel);val scope=c.historyScope
            a.get().getSharedPreferences("native-library-v1",0).edit().putString("favorites","local-must-stay").commit()
            c.switchAccount("b");drain(c)
            assertEquals("乙",c.accountLabel);assertNotEquals(scope,c.historyScope);assertEquals("b",s.load().active?.profile?.id)
            assertEquals("https://example.org/b.png",c.accountProfile?.avatar);assertNotNull(c.recommendationCredentials())
            assertEquals("local-must-stay",a.get().getSharedPreferences("native-library-v1",0).getString("favorites",null))
            c.useLocalOnly();drain(c);assertFalse(c.signedIn);assertEquals(2,s.load().accounts.size)
        } finally {c.destroy();a.pause().stop().destroy()}
    }
    @Test fun delayedOldHistoryCannotRepopulateAccountAfterSwitch(){
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val (a,c,_)=fixture(http {r->if(r.header("Cookie").orEmpty().endsWith("-a") && r.url.encodedPath.contains("read_progress/list")){entered.countDown();release.await(6,TimeUnit.SECONDS);"{\"code\":0,\"video_data\":[{\"series_id\":\"101\",\"vid\":\"102\",\"vid_index\":1}]}"}else if(r.url.encodedPath.contains("multi-detail"))"{\"code\":0,\"data\":[{\"book_id\":\"101\",\"book_name\":\"旧账号专属记录\"}]}" else body(r)})
        try {
            (c.javaClass.getDeclaredField("worker").apply{isAccessible=true}.get(c) as ExecutorService).submit{}.get(5,TimeUnit.SECONDS);ShadowLooper.idleMainLooper()
            assertTrue(entered.await(5,TimeUnit.SECONDS));val scope=c.historyScope;c.switchAccount("b");assertNotEquals(scope,c.historyScope)
            assertTrue(c.historyRows().isEmpty());assertNull(c.recommendationCredentials());release.countDown();drain(c)
            assertEquals("乙",c.accountLabel);assertTrue(c.historyRows().isEmpty());assertFalse(c.historyLoading)
        } finally {release.countDown();c.destroy();a.pause().stop().destroy()}
    }
    @Test fun mismatchedIdentityCannotUploadOrChangeCloudFavorites(){
        var writes=0
        val (a,c,_)=fixture(http {r->if(r.method=="POST")writes++;if(r.url.encodedPath.contains("user/info"))"{\"code\":0,\"data\":{\"biz_user_id\":\"other\",\"user_name\":\"错误账号\"}}" else body(r)})
        try {drain(c);assertNull(c.recommendationCredentials());assertTrue(c.historyStatus.contains("重新登录"))
            c.upload(CloudProgress(Series("1","本机"),"11",0,1000,90000,1),2,true)
            c.changeFavorite(Series("1","本机"),true){};drain(c);assertEquals(0,writes)
        } finally {c.destroy();a.pause().stop().destroy()}
    }
    @Test fun progressFromOldAccountIsNeverUploadedWithNewAccountsCredentials(){
        val uploads=CopyOnWriteArrayList<Pair<String,String>>()
        val (a,c,_)=fixture(http {r->if(r.url.encodedPath.contains("read_progress/upload")){val buffer=okio.Buffer();r.body!!.writeTo(buffer);uploads+=r.header("Cookie").orEmpty() to JSONObject(buffer.readUtf8()).getJSONArray("books").getJSONObject(0).getString("book_id")};body(r)})
        try {drain(c);c.upload(CloudProgress(Series("1","甲的记录"),"11",0,1000,90000,1),2,true);c.switchAccount("b");drain(c)
            c.upload(CloudProgress(Series("2","乙的记录"),"21",0,2000,90000,2),2,true);drain(c)
            assertTrue(uploads.any{it.first.endsWith("-b")&&it.second=="2"});assertFalse(uploads.any{it.first.endsWith("-b")&&it.second=="1"})
        } finally {c.destroy();a.pause().stop().destroy()}
    }
    @Test fun localOnlyIntentPersistsEvenWhenActivityClosesBeforeWorkerCanApplyIt(){
        val (a,c,s)=fixture(http(::body));val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {drain(c);val worker=c.javaClass.getDeclaredField("worker").apply{isAccessible=true}.get(c) as ExecutorService
            worker.submit {entered.countDown();release.await(5,TimeUnit.SECONDS)};assertTrue(entered.await(5,TimeUnit.SECONDS))
            c.useLocalOnly();c.destroy();release.countDown();assertTrue(worker.awaitTermination(8,TimeUnit.SECONDS))
            assertNull(s.load().active);assertEquals(2,s.load().accounts.size)
        } finally {release.countDown();a.pause().stop().destroy()}
    }
    @Test fun logoutIntentForgetsOnlyCurrentAccountEvenAfterImmediateDestroy(){
        val (a,c,s)=fixture(http(::body));val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {drain(c);val worker=c.javaClass.getDeclaredField("worker").apply{isAccessible=true}.get(c) as ExecutorService
            worker.submit {entered.countDown();release.await(5,TimeUnit.SECONDS)};assertTrue(entered.await(5,TimeUnit.SECONDS))
            c.javaClass.getDeclaredMethod("logout").apply{isAccessible=true}.invoke(c);c.destroy();release.countDown();assertTrue(worker.awaitTermination(8,TimeUnit.SECONDS))
            assertEquals(listOf("b"),s.load().accounts.map{it.profile.id});assertNull(s.load().active)
        } finally {release.countDown();a.pause().stop().destroy()}
    }

    @Test fun completedOldFavoriteAndReservationCallbacksCannotFlashUnderNewAccount(){
        var oldRows=false
        val (a,c,_)=fixture(http {r->
            if(oldRows && r.header("Cookie").orEmpty().endsWith("-a") && r.url.encodedPath.contains("bookshelf/video/info"))
                "{\"code\":0,\"data\":{\"video_shelf_info\":[{\"series_title\":\"甲的收藏\",\"video_data\":{\"series_id\":\"301\"}}]}}"
            else if(oldRows && r.header("Cookie").orEmpty().endsWith("-a") && r.url.encodedPath.contains("subscribe/list"))
                "{\"code\":0,\"data\":{\"subscribe_items\":[{\"subscribe_data\":{\"series_id\":\"302\",\"title\":\"甲的预约\"}}]}}"
            else body(r)
        })
        try {drain(c);oldRows=true;c.refreshFavorites();c.refreshReservations()
            val worker=c.javaClass.getDeclaredField("worker").apply{isAccessible=true}.get(c) as ExecutorService
            worker.submit{}.get(5,TimeUnit.SECONDS) // Responses completed, main callbacks intentionally pending.
            val seen=mutableListOf<String>();c.onHistoryChanged={seen+=c.favoriteState().rows.map{it.series.id};seen+=c.reservationState(false).rows.map{it.series.id}}
            c.switchAccount("b");drain(c);assertFalse(seen.contains("301"));assertFalse(seen.contains("302"));assertEquals("乙",c.accountLabel)
        } finally {c.destroy();a.pause().stop().destroy()}
    }
    @Test fun failedSelectionPersistenceKeepsStoredAAndDoesNotActivateB(){
        val a=Robolectric.buildActivity(Activity::class.java).setup();var broken=false;val base=FixtureAccountCipher()
        val cipher=object:AccountCipher{override fun encrypt(raw:ByteArray):ByteArray {if(broken)throw java.io.IOException("fixture disk failure");return base.encrypt(raw)};override fun decrypt(raw:ByteArray)=base.decrypt(raw)}
        a.get().getSharedPreferences("cloud_account_secure",0).edit().clear().commit();val store=AccountStore(a.get(),cipher)
        store.save(credential("b"),CloudProfile("b","乙"));store.save(credential("a"),CloudProfile("a","甲"));val c=AccountController(a.get(),{},store,http(::body))
        try {drain(c);broken=true;c.switchAccount("b");drain(c);assertFalse(c.signedIn);assertNull(c.recommendationCredentials());assertEquals("a",store.load().active?.profile?.id);assertTrue(c.historyStatus.contains("未保存"))}
        finally {c.destroy();a.pause().stop().destroy()}
    }

}
