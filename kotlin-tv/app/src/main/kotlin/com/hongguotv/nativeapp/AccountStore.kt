// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.CloudProfile
import com.hongguotv.core.accountAvatarUrl
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

internal interface AccountCipher {
    fun encrypt(raw:ByteArray):ByteArray
    fun decrypt(raw:ByteArray):ByteArray
    fun reset() {}
}
private class KeystoreAccountCipher:AccountCipher {
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(ALIAS,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun encrypt(raw:ByteArray):ByteArray=Cipher.getInstance("AES/GCM/NoPadding").let {it.init(Cipher.ENCRYPT_MODE,key());it.iv+it.doFinal(raw)}
    override fun decrypt(raw:ByteArray):ByteArray {
        require(raw.size>28){"登录态数据不完整"}
        return Cipher.getInstance("AES/GCM/NoPadding").let {it.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,raw.copyOfRange(0,12)));it.doFinal(raw.copyOfRange(12,raw.size))}
    }
    override fun reset(){KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(ALIAS)}}
    private companion object {const val ALIAS="hongguotv.cloud.account.v1"}
}
internal data class StoredAccount(val profile:CloudProfile,val credentials:AccountCredentials,val lastUsedAt:Long) {
    override fun toString()="StoredAccount([redacted])"
}
internal data class AccountVault(val accounts:List<StoredAccount> = emptyList(),val activeId:String? = null,
    val legacy:AccountCredentials? = null,val legacyActive:Boolean = false) {
    val active get()=accounts.firstOrNull {it.profile.id==activeId}
    val count get()=accounts.size+if(legacy!=null)1 else 0
    override fun toString()="AccountVault(count=$count, [redacted])"
}
/** One private encrypted partition; the same alias decrypts the previous single-session format. */
internal class AccountStore(context:Context,private val cipher:AccountCipher=KeystoreAccountCipher()) {
    private val prefs=context.applicationContext.getSharedPreferences("cloud_account_secure",Context.MODE_PRIVATE)
    fun load():AccountVault=synchronized(lock){read()}
    fun save(credentials:AccountCredentials,profile:CloudProfile):AccountVault=synchronized(lock){
        validateCredentials(credentials);validateProfile(profile)
        val old=read();val legacy=old.legacy?.takeUnless {it.cookie==credentials.cookie}
        val rows=listOf(StoredAccount(profile.copy(avatar=accountAvatarUrl(profile.avatar)),credentials,System.currentTimeMillis()))+old.accounts.filterNot{it.profile.id==profile.id}
        require(rows.size+(if(legacy!=null)1 else 0)<=MAX_ACCOUNTS){"最多保存10个账号，请先移除不再使用的账号"}
        write(AccountVault(rows,profile.id,legacy,false))
    }
    fun select(id:String):AccountVault=synchronized(lock){
        val old=read();val selected=old.accounts.firstOrNull{it.profile.id==id} ?: error("该账号已移除，请重新登录")
        write(old.copy(accounts=listOf(selected.copy(lastUsedAt=System.currentTimeMillis()))+old.accounts.filterNot{it.profile.id==id},activeId=id,legacyActive=false))
    }
    fun selectLegacy():AccountVault=synchronized(lock){val old=read();check(old.legacy!=null){"旧版登录态已移除"};write(old.copy(activeId=null,legacyActive=true))}
    fun deactivate():AccountVault=synchronized(lock){write(read().copy(activeId=null,legacyActive=false))}
    fun remove(id:String):AccountVault=synchronized(lock){val old=read();write(old.copy(accounts=old.accounts.filterNot{it.profile.id==id},activeId=old.activeId.takeUnless{it==id}))}
    fun forgetLegacy():AccountVault=synchronized(lock){write(read().copy(legacy=null,legacyActive=false))}
    /** Only an explicit recovery confirmation may discard unreadable logins; local Library is separate. */
    fun resetUnreadable():AccountVault=synchronized(lock){
        check(runCatching{read()}.isFailure){"登录态已可读取，请重新打开账号列表"}
        check(prefs.edit().clear().commit()){"清除损坏登录态失败"}
        cipher.reset()
        AccountVault()
    }
    private fun decode(raw:String):ByteArray {
        require(raw.length<=3*1024*1024){"登录态数据过大"}
        return cipher.decrypt(Base64.decode(raw,Base64.NO_WRAP)).also {require(it.size<=2*1024*1024)}
    }
    private fun read():AccountVault {
        val raw=prefs.getString(VAULT,null)
        if(raw==null) {
            val legacy=prefs.getString("session",null) ?: return AccountVault()
            val credentials=AccountCredentials.fromJson(String(decode(legacy),Charsets.UTF_8));validateCredentials(credentials)
            return AccountVault(legacy=credentials,legacyActive=true)
        }
        val root=JSONObject(String(decode(raw),Charsets.UTF_8));require(root.getInt("version")==2){"登录态版本不支持"}
        val array=root.getJSONArray("accounts");require(array.length()<=MAX_ACCOUNTS)
        val rows=(0 until array.length()).map {i->val row=array.getJSONObject(i);val profile=CloudProfile(row.getString("id"),row.getString("nickname"),row.optString("avatar"))
            val credentials=AccountCredentials.fromJson(row.getString("credentials"));validateProfile(profile);validateCredentials(credentials)
            StoredAccount(profile.copy(avatar=accountAvatarUrl(profile.avatar)),credentials,row.getLong("at").also {require(it>=0)})}
        require(rows.map{it.profile.id}.distinct().size==rows.size){"登录态包含重复账号"}
        val active=root.optString("active").takeIf{it.isNotEmpty()};require(active==null || rows.any{it.profile.id==active})
        val legacy=root.optString("legacy").takeIf{it.isNotEmpty()}?.let {AccountCredentials.fromJson(it).also(::validateCredentials)}
        val legacyActive=root.optBoolean("legacyActive",false);require(!legacyActive || (legacy!=null && active==null))
        return AccountVault(rows,active,legacy,legacyActive).also{require(it.count<=MAX_ACCOUNTS)}
    }
    private fun write(vault:AccountVault):AccountVault {
        val root=JSONObject().put("version",2).put("active",vault.activeId.orEmpty()).put("legacyActive",vault.legacyActive)
            .put("legacy",vault.legacy?.toJson().orEmpty()).put("accounts",JSONArray(vault.accounts.map {row->JSONObject()
                .put("id",row.profile.id).put("nickname",row.profile.nickname).put("avatar",row.profile.avatar)
                .put("credentials",row.credentials.toJson()).put("at",row.lastUsedAt)}))
        val bytes=root.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=2*1024*1024){"登录态数据过大"}
        val sealed=Base64.encodeToString(cipher.encrypt(bytes),Base64.NO_WRAP)
        check(prefs.edit().putString(VAULT,sealed).remove("session").commit()){"保存登录态失败"}
        return vault
    }
    private fun validateProfile(profile:CloudProfile){require(profile.id.isNotBlank() && profile.id.length<=128 && profile.id.none(Char::isISOControl));require(profile.nickname.length<=200 && profile.nickname.none(Char::isISOControl));require(profile.avatar.length<=4096)}
    private fun validateCredentials(value:AccountCredentials){require(value.cookie.isNotBlank() && value.cookie.length<=65536 && value.cookie.none{it=='\r'||it=='\n'});require(value.toJson().toByteArray().size<=256*1024)}
    companion object {const val MAX_ACCOUNTS=10;private const val VAULT="vault_v2";private val lock=Any()}
}
