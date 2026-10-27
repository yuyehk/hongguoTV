package com.hongguotv.nativeapp
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.app.AlertDialog
import android.view.*
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hongguotv.core.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
@RunWith(AndroidJUnit4::class)
class AccountSwitchIntegrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun <T> ui(action:()->T):T {val out=AtomicReference<T>();val err=AtomicReference<Throwable>();instrumentation.runOnMainSync{try{out.set(action())}catch(t:Throwable){err.set(t)}};err.get()?.let{throw it};return out.get()}
    private fun field(a:Any,name:String)=a.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(a)
    private fun set(a:Any,name:String,v:Any?)=a.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(a,v)
    private fun waitFor(action:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(ui(action))return;Thread.sleep(50)};fail("Timed out waiting for account/header state")}
    @Test fun actualKeystoreSwitchHeaderAndLegacyMigrationPreserveNativeRecords(){
        val context=instrumentation.targetContext;val prefs=context.getSharedPreferences("cloud_account_secure",0)
        val original=prefs.all.mapValues{it.value as String};assertEquals(0,AccountStore(context).load().count)
        val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null)};val keyExisted=keys.containsAlias("hongguotv.cloud.account.v1")
        val native=context.getSharedPreferences("native-library-v1",0);val nativeBefore=listOf("favorites","progress").associateWith{native.getString(it,"")}
        val identity=context.getSharedPreferences("device_identity",0).getString("device_id",null)
        val images=Images();var activity:MainActivity?=null;var controller:AccountController?=null
        val accountHttp=OkHttpClient.Builder().addInterceptor {chain->
            val r=chain.request();val id=if(r.header("Cookie").orEmpty().endsWith("b"))"b" else "a"
            val body=when {r.url.encodedPath.contains("user/info")->"{\"code\":0,\"data\":{\"biz_user_id\":\"$id\",\"user_name\":\"${if(id=="a")"甲账号" else "乙账号"}\",\"avatar_url\":\"${images.base}/$id.png\"}}"
                r.url.encodedPath.contains("read_progress/list")->"{\"code\":0,\"video_data\":[]}"
                r.url.encodedPath.contains("bookshelf/video/info")->"{\"code\":0,\"data\":{\"video_shelf_info\":[]}}"
                else->"{\"code\":0,\"data\":{\"subscribe_items\":[]}}"}
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        try {
            activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val main=activity!!;waitFor{(field(main,"library") as Library).isLoaded}
            val old=ui{field(main,"cloudAccount") as AccountController};(field(old,"worker") as java.util.concurrent.ExecutorService).submit{}.get(5,java.util.concurrent.TimeUnit.SECONDS);instrumentation.waitForIdleSync()
            ui{val guest=(field(main,"root") as View).findViewWithTag<AccountAvatarView>("account-avatar")
                val login=guest.findViewWithTag<TextView>("account-login");assertEquals("登录",login.text.toString());assertEquals(View.VISIBLE,login.visibility)
                @Suppress("UNCHECKED_CAST") val nav=field(main,"nav") as List<View>;assertFalse(nav.filterIsInstance<TextView>().any{it.text.toString()=="账户"})
                guest.performClick();val menu=field(old,"dialog") as AlertDialog;assertTrue((0 until menu.listView.adapter.count).any{menu.listView.adapter.getItem(it).toString().contains("扫码")});menu.dismiss()}
            instrumentation.uiAutomation.takeScreenshot()?.let {bitmap->java.io.FileOutputStream(java.io.File(context.cacheDir,"multi-account-login.png")).use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            ui{old.onHistoryChanged=null;old.destroy();set(main,"repository",ContentRepository(OkHttpClient.Builder().addInterceptor{throw java.io.IOException("offline header fixture")}.build()))}
            val store=AccountStore(context);val a=AccountCredentials("sessionid=native-fixture-a");val b=AccountCredentials("sessionid=native-fixture-b")
            store.save(b,CloudProfile("b","乙账号","${images.base}/b.png"));store.save(a,CloudProfile("a","甲账号","${images.base}/a.png"))
            ui{controller=AccountController(main,{},store,accountHttp);set(main,"cloudAccount",controller);controller!!.onHistoryChanged={MainActivity::class.java.getDeclaredMethod("refreshAccountAvatar").apply{isAccessible=true}.invoke(main)}
                MainActivity::class.java.getDeclaredMethod("showCatalog",Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(main,false,false,false)}
            waitFor{controller!!.accountLabel=="甲账号" && controller!!.recommendationCredentials()!=null}
            val avatar=ui{(field(main,"root") as View).findViewWithTag<AccountAvatarView>("account-avatar")}
            waitFor{(avatar.findViewWithTag<ImageView>("account-avatar-image").drawable as? BitmapDrawable)?.bitmap?.getPixel(16,16)==Color.BLUE}
            ui{val xy=IntArray(2);avatar.getLocationOnScreen(xy);assertTrue(xy[0]>main.resources.displayMetrics.widthPixels*.8)
                var parent=avatar.parent;while(parent is View){assertFalse(parent is HorizontalScrollView);parent=parent.parent}
                assertTrue("Avatar must be attached",avatar.isAttachedToWindow);assertTrue(avatar.requestFocus());assertTrue(avatar.hasFocus())}
            waitFor{main.hasWindowFocus() && avatar.hasFocus()}
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor{field(controller!!,"dialog") is AlertDialog}
            ui{val menu=field(controller!!,"dialog") as AlertDialog;val list=menu.listView;val labels=(0 until list.adapter.count).map{list.adapter.getItem(it).toString()}
                assertTrue(labels.any{it.contains("云端继续观看")});assertTrue(labels.contains("管理已保存账号"));val index=labels.indexOf("切换已保存账号");assertTrue(index>=0);list.performItemClick(list.adapter.getView(index,null,list),index,list.adapter.getItemId(index))}
            ui{val dialog=field(controller!!,"dialog") as AlertDialog;val list=dialog.listView;val index=(0 until list.adapter.count).first{list.adapter.getItem(it).toString().contains("乙账号")};list.performItemClick(list.adapter.getView(index,null,list),index,list.adapter.getItemId(index))}
            waitFor{controller!!.accountLabel=="乙账号" && controller!!.recommendationCredentials()!=null}
            waitFor{(avatar.findViewWithTag<ImageView>("account-avatar-image").drawable as? BitmapDrawable)?.bitmap?.getPixel(16,16)==Color.YELLOW}
            ui{assertTrue(avatar.requestFocus());assertNotNull("Avatar focus border must overlay the loaded image",avatar.foreground)}
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap->java.io.FileOutputStream(java.io.File(context.cacheDir,"multi-account-header.png")).use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle() }
            assertEquals("b",store.load().active?.profile?.id);assertTrue(images.headers.none{it.contains("cookie:",true)||it.contains("authorization:",true)})
            ui{controller!!.onHistoryChanged=null;controller!!.destroy()}
            // Produce the exact previous v1 encrypted session with the actual Android Keystore key.
            val key=keys.getKey("hongguotv.cloud.account.v1",null) as javax.crypto.SecretKey
            val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply{init(javax.crypto.Cipher.ENCRYPT_MODE,key)}
            val legacy=android.util.Base64.encodeToString(cipher.iv+cipher.doFinal(a.toJson().toByteArray()),android.util.Base64.NO_WRAP)
            prefs.edit().clear().putString("session",legacy).commit()
            ui{controller=AccountController(main,{},store,accountHttp);set(main,"cloudAccount",controller)}
            waitFor{controller!!.recommendationCredentials()!=null};assertFalse(prefs.contains("session"));assertEquals("a",store.load().active?.profile?.id)
            assertEquals(nativeBefore,listOf("favorites","progress").associateWith{native.getString(it,"")});assertEquals(identity,context.getSharedPreferences("device_identity",0).getString("device_id",null))
            android.util.Log.i("AccountSwitchTest","Actual AndroidKeyStore: avatar A/B switch, credential-free image HTTP, v1 migration, local-record isolation passed")
        } finally {
            ui{controller?.onHistoryChanged=null;activity?.finish()};instrumentation.waitForIdleSync()
            val edit=prefs.edit().clear();original.forEach{(k,v)->edit.putString(k,v)};edit.commit()
            if(!keyExisted)keys.deleteEntry("hongguotv.cloud.account.v1")
            images.close();accountHttp.dispatcher.cancelAll();accountHttp.connectionPool.evictAll();accountHttp.dispatcher.executorService.shutdown()
        }
    }
    private class Images {
        private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));private val jobs=Executors.newCachedThreadPool();val headers=CopyOnWriteArrayList<String>();val base="http://127.0.0.1:${socket.localPort}"
        init{Thread{while(!socket.isClosed){val client=runCatching{socket.accept()}.getOrNull()?:break;jobs.execute{runCatching{client.use{s->s.soTimeout=3000;val reader=s.getInputStream().bufferedReader();val first=reader.readLine();val h=StringBuilder();while(true){val line=reader.readLine()?:break;if(line.isEmpty())break;h.append(line).append('\n')};headers+=h.toString()
            val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply{eraseColor(if(first.contains("/a.png"))Color.BLUE else Color.YELLOW)};val out=java.io.ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();val bytes=out.toByteArray();s.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}}}}}}.apply{isDaemon=true;start()}}
        fun close(){socket.close();jobs.shutdownNow()}
    }
}
