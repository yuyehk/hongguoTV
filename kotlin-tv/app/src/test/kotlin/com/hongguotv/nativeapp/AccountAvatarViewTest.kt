package com.hongguotv.nativeapp
import android.app.Activity
import android.view.View
import android.widget.ImageView
import com.hongguotv.core.CloudProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class AccountAvatarViewTest {
    @Test fun guestIsFocusableAndClickOpensSwitcherWithVisibleFallback(){
        val a=Robolectric.buildActivity(Activity::class.java).setup();var clicked=0
        try { val v=AccountAvatarView(a.get()){clicked++};a.get().setContentView(v)
            v.bind(null,false){_,url->assertEquals("",url)}
            assertTrue(v.isFocusable);assertTrue(v.contentDescription.contains("未登录"));assertEquals(View.GONE,v.findViewWithTag<View>("account-avatar-placeholder").visibility)
            val login=v.findViewWithTag<android.widget.TextView>("account-login");assertNotNull(login);assertEquals("登录",login.text.toString());assertEquals(View.VISIBLE,login.visibility)
            v.performClick();assertEquals(1,clicked)
        } finally {a.pause().stop().destroy()}
    }
    @Test fun rebindingClearsOldFaceBeforeNewImageAndMissingAvatarKeepsPlaceholder(){
        val a=Robolectric.buildActivity(Activity::class.java).setup()
        try {val v=AccountAvatarView(a.get()){};a.get().setContentView(v);val image=v.findViewWithTag<ImageView>("account-avatar-image")
            var requests=0;v.bind(CloudProfile("a","甲","https://example.org/a"),true){target,_->requests++;target.setImageDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.RED))}
            assertNotNull(image.drawable)
            v.bind(CloudProfile("b","乙"),true){_,url->assertEquals("",url)}
            assertNull(image.drawable);assertTrue(v.contentDescription.contains("乙"));assertEquals(1,requests)
        } finally {a.pause().stop().destroy()}
    }
    @Test fun unchangedAvatarRefreshKeepsRenderedBitmapWhileNicknameUpdates(){
        val a=Robolectric.buildActivity(Activity::class.java).setup()
        try {val v=AccountAvatarView(a.get()){};val face=android.graphics.drawable.ColorDrawable(android.graphics.Color.BLUE)
            v.bind(CloudProfile("a","甲","https://example.org/a"),true){image,_->image.setImageDrawable(face)}
            v.bind(CloudProfile("a","新昵称","https://example.org/a"),true){_,_->fail("unchanged image must keep its binding")}
            assertSame(face,v.findViewWithTag<ImageView>("account-avatar-image").drawable);assertTrue(v.contentDescription.contains("新昵称"))
        } finally {a.pause().stop().destroy()}
    }

}
