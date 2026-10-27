package com.hongguotv.core
import org.junit.Assert.*
import org.junit.Test
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
class CloudProfileAvatarTest {
    private fun profile(fields:String):CloudProfile {
        val http=OkHttpClient.Builder().addInterceptor { chain->Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(("{\"code\":0,\"data\":{\"biz_user_id\":\"00123\",\"user_name\":\"测试用户\","+fields+"}}").toResponseBody()).build() }.build()
        return CloudAccountClient(http).profile(AccountCredentials("sessionid=fixture"))
    }
    @Test fun avatarUsesVerifiedInfoFieldsAndPreservesLeadingZeroIdentity() {
        val p=profile("\"avatar_url\":\"https://images.example/a.png?x=1&amp;y=2\"")
        assertEquals("00123",p.id);assertEquals("https://images.example/a.png?x=1&y=2",p.avatar)
        assertEquals("https://images.example/fallback.png",profile("\"expand_avatar_url\":\"https://images.example/fallback.png\"").avatar)
    }
    @Test fun unsafeOrMissingAvatarNeverBreaksAnOtherwiseValidProfile() {
        for(url in listOf("javascript:alert(1)","file:///secret","https://user:password@example.org/a","https://example.org/a\\n")) {
            assertEquals("",profile("\"avatar_url\":"+org.json.JSONObject.quote(url)).avatar)
        }
        assertEquals("",profile("\"unused\":true").avatar)
        assertEquals("https://example.org/ok",profile("\"avatar_url\":\"file:///bad\",\"expand_avatar_url\":\"https://example.org/ok\"").avatar)
    }
}
