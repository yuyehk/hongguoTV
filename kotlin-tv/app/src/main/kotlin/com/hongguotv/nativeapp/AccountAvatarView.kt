// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.view.Gravity
import com.hongguotv.core.CloudProfile
import com.hongguotv.core.accountAvatarUrl

/** Fixed header action; the placeholder stays behind the credential-free, cancellable image. */
internal class AccountAvatarView(context:Context,onOpen:()->Unit):FrameLayout(context) {
    private fun dp(n:Int)=(resources.displayMetrics.density*n).toInt()
    private var bound: Triple<Boolean,String?,String>? = null
    val actionWidthPx get()=dp(if(bound?.first==true)44 else 76)
    private val image=ImageView(context).apply {tag="account-avatar-image";scaleType=ImageView.ScaleType.CENTER_CROP}
    private val login=TextView(context).apply{tag="account-login";text="登录";textSize=16f;gravity=Gravity.CENTER;setTextColor(TvStyle.text);setTypeface(null,Typeface.BOLD)}
    init {
        tag="account-avatar";isFocusable=true;isFocusableInTouchMode=true;isClickable=true
        minimumWidth=actionWidthPx;minimumHeight=dp(44);clipToOutline=true
        addView(ImageView(context).apply {tag="account-avatar-placeholder";setImageDrawable(Person())},LayoutParams(-1,-1))
        addView(image,LayoutParams(-1,-1));addView(login,LayoutParams(-1,-1));paintFocus(false)
        setOnFocusChangeListener {_,focused->paintFocus(focused)};setOnClickListener{onOpen()}
    }
    private fun paintFocus(focused:Boolean){
        val avatar=bound?.first==true
        background=GradientDrawable().apply{shape=if(avatar)GradientDrawable.OVAL else GradientDrawable.RECTANGLE;cornerRadius=dp(10).toFloat();setColor(if(focused && !avatar)TvStyle.raised else TvStyle.surface)}
        // A loaded avatar covers the background; draw its focus border above the image.
        foreground=GradientDrawable().apply{shape=if(avatar)GradientDrawable.OVAL else GradientDrawable.RECTANGLE;cornerRadius=dp(10).toFloat();setColor(Color.TRANSPARENT);setStroke(dp(if(focused)2 else 1),if(focused)TvStyle.accent else TvStyle.outline)}
    }
    fun bind(profile:CloudProfile?,signedIn:Boolean,load:(ImageView,String)->Unit) {
        contentDescription=if(signedIn)"当前账号：${profile?.nickname ?: "红果账号"}，打开账号功能" else "未登录，登录或管理已保存账号"
        val url=if(signedIn)accountAvatarUrl(profile?.avatar.orEmpty())else ""
        val next=Triple(signedIn,profile?.id.takeIf{signedIn},url)
        if(next==bound)return // ArtworkLoader's applied fast path expects its image to stay intact.
        if(bound?.third==url && url.isNotEmpty())load(image,"") // New identity with a shared image URL.
        bound=next;image.setImageDrawable(null)
        login.visibility=if(signedIn)View.GONE else View.VISIBLE
        image.visibility=if(signedIn)View.VISIBLE else View.GONE
        findViewWithTag<View>("account-avatar-placeholder").visibility=if(signedIn)View.VISIBLE else View.GONE
        minimumWidth=actionWidthPx;layoutParams?.let{it.width=actionWidthPx;layoutParams=it};paintFocus(hasFocus())
        load(image,url) // Empty URL cancels an earlier user's asynchronous artwork binding.
    }
    private class Person:Drawable() {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas:Canvas){paint.color=TvStyle.muted;val x=bounds.exactCenterX();val y=bounds.exactCenterY();val r=minOf(bounds.width(),bounds.height())*.15f
            canvas.drawCircle(x,y-r*.7f,r,paint);canvas.drawOval(x-r*1.65f,y+r*.65f,x+r*1.65f,y+r*2.5f,paint)}
        override fun setAlpha(alpha:Int){paint.alpha=alpha};override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
        @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}
