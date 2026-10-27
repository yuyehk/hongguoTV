package com.hongguotv.nativeapp
import android.content.Context
import com.hongguotv.core.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal fun offlineAccountHttp()=okhttp3.OkHttpClient.Builder().addInterceptor {throw java.io.IOException("offline account fixture")}.build()
internal class FixtureAccountCipher: AccountCipher {
    private val key=SecretKeySpec(ByteArray(16){(it+1).toByte()},"AES")
    override fun encrypt(raw:ByteArray):ByteArray = Cipher.getInstance("AES/GCM/NoPadding").let {it.init(Cipher.ENCRYPT_MODE,key);it.iv+it.doFinal(raw)}
    override fun decrypt(raw:ByteArray):ByteArray = Cipher.getInstance("AES/GCM/NoPadding").let {it.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,raw.copyOfRange(0,12)));it.doFinal(raw.copyOfRange(12,raw.size))}
}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class AccountStoreTest {
    private val app get()=RuntimeEnvironment.getApplication() as Context
    private val cipher=FixtureAccountCipher()
    private val prefs get()=app.getSharedPreferences("cloud_account_secure",0)
    private fun cred(id:String)=AccountCredentials("sessionid=private-$id","device-$id","install-$id")
    @Before fun reset(){prefs.edit().clear().commit()}
    @Test fun twoAccountsRemainEncryptedAndSwitchPersistsAcrossInstances(){
        val s=AccountStore(app,cipher);s.save(cred("a"),CloudProfile("a","甲","https://example.org/a"));s.save(cred("b"),CloudProfile("b","乙"))
        assertEquals("a",s.select("a").active?.profile?.id)
        val loaded=AccountStore(app,cipher).load();assertEquals(2,loaded.accounts.size);assertEquals(cred("a"),loaded.active?.credentials)
        assertFalse(prefs.all.toString().contains("private-a"));assertFalse(prefs.all.toString().contains("device-a"));assertFalse(loaded.toString().contains("private-a"))
    }
    @Test fun duplicateLoginUpdatesOnlyThatUsersSessionAndNeverEvictsAtCapacity(){
        val s=AccountStore(app,cipher);repeat(10){s.save(cred("$it"),CloudProfile("$it","用户$it"))}
        s.save(cred("renewed"),CloudProfile("0","新昵称"));assertEquals(10,s.load().accounts.size)
        assertTrue(runCatching{s.save(cred("overflow"),CloudProfile("overflow","额外"))}.isFailure)
        assertEquals(10,s.load().accounts.size);assertEquals("新昵称",s.select("0").active?.profile?.nickname)
    }
    @Test fun legacySessionSurvivesUntilMatchingIdentityIsValidated(){
        val legacy=cred("legacy");prefs.edit().putString("session",android.util.Base64.encodeToString(cipher.encrypt(legacy.toJson().toByteArray()),2)).commit()
        val s=AccountStore(app,cipher);assertEquals(legacy,s.load().legacy)
        s.save(cred("other"),CloudProfile("b","乙"));assertEquals(legacy,s.load().legacy)
        assertFalse(prefs.contains("session"));assertNotNull(s.selectLegacy().legacy)
        s.save(legacy,CloudProfile("a","甲"));assertNull(s.load().legacy);assertEquals(setOf("a","b"),s.load().accounts.map{it.profile.id}.toSet())
    }
    @Test fun guestAndRemovingOneLoginNeverEraseAnotherOrNativeRecords(){
        app.getSharedPreferences("native-library-v1",0).edit().putString("favorites","keep-local").commit()
        val s=AccountStore(app,cipher);s.save(cred("a"),CloudProfile("a","甲"));s.save(cred("b"),CloudProfile("b","乙"))
        assertNull(s.deactivate().active);assertEquals(2,s.load().accounts.size)
        s.remove("a");assertEquals(listOf("b"),s.load().accounts.map{it.profile.id});assertEquals("keep-local",app.getSharedPreferences("native-library-v1",0).getString("favorites",null))
    }
    @Test fun corruptedCiphertextFailsClosedAndDoesNotOverwriteAnyBytes(){
        val s=AccountStore(app,cipher);s.save(cred("a"),CloudProfile("a","甲"))
        val key=prefs.all.keys.single();val old=prefs.getString(key,null)!!;val bytes=android.util.Base64.decode(old,2);bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
        val bad=android.util.Base64.encodeToString(bytes,2);prefs.edit().putString(key,bad).commit()
        assertTrue(runCatching{s.load()}.isFailure);assertTrue(runCatching{s.save(cred("b"),CloudProfile("b","乙"))}.isFailure);assertEquals(bad,prefs.getString(key,null))
    }
    @Test fun explicitUnreadableLoginResetRecoversWithoutClearingNativeLibrary(){
        val s=AccountStore(app,cipher);s.save(cred("a"),CloudProfile("a","甲"))
        assertTrue(runCatching{s.resetUnreadable()}.isFailure) // readable logins require normal management
        prefs.edit().putString("vault_v2","broken encrypted fixture").commit()
        app.getSharedPreferences("native-library-v1",0).edit().putString("favorites","preserved").commit()
        assertEquals(0,s.resetUnreadable().count)
        s.save(cred("b"),CloudProfile("b","乙"));assertEquals("b",s.load().active?.profile?.id)
        assertEquals("preserved",app.getSharedPreferences("native-library-v1",0).getString("favorites",null))
    }

}
