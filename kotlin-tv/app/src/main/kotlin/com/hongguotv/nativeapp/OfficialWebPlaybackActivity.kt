// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import java.net.URI

private const val OFFICIAL_PLAYBACK_HOST = "hongguoduanju.com"
private val episodeIdPattern = Regex("[0-9]{1,30}")

internal fun officialEpisodePage(seriesId: String, episodeId: String?): String? {
    if (!episodeIdPattern.matches(seriesId) ||
        episodeId == null || !episodeIdPattern.matches(episodeId)
    ) return null
    return "https://$OFFICIAL_PLAYBACK_HOST/player/$seriesId/$episodeId"
}

internal fun isOfficialPlaybackNavigation(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals(OFFICIAL_PLAYBACK_HOST, ignoreCase = true) &&
        uri.userInfo == null && uri.port in listOf(-1, 443)
}

/** Hosts the normal public website and gives a DPAD remote the same click controls as a mouse. */
class OfficialWebPlaybackActivity : Activity() {
    private lateinit var container: FrameLayout
    private lateinit var cursor: View
    private var web: WebView? = null
    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var cursorX = Float.NaN
    private var cursorY = Float.NaN
    private val hideCursor = Runnable { cursor.visibility = View.INVISIBLE }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra(EXTRA_PAGE)
        if (page == null || !isOfficialPlaybackNavigation(page)) {
            finish()
            return
        }
        val browser = try {
            WebView(this)
        } catch (_: RuntimeException) {
            Toast.makeText(this, "电视的网页组件暂时不可用。", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        web = browser
        container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContentView(container)
        container.addView(browser, FrameLayout.LayoutParams(-1, -1))
        cursor = View(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(2), Color.BLACK)
            }
            visibility = View.INVISIBLE
        }
        container.addView(cursor, FrameLayout.LayoutParams(dp(16), dp(16)))
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Use the provider's Chromium version with the website's desktop layout on TV.
            val chrome = Regex("Chrome/[^ ]+").find(userAgentString)?.value ?: "Chrome/113.0.0.0"
            userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                request.isForMainFrame && !isOfficialPlaybackNavigation(request.url.toString())

            @Deprecated("Required by Android 6 WebView")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) =
                !isOfficialPlaybackNavigation(url)
        }
        browser.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                if (fullscreen != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreen = view
                fullscreenCallback = callback
                container.addView(view, FrameLayout.LayoutParams(-1, -1))
                cursor.bringToFront()
            }

            override fun onHideCustomView() = closeFullscreen()
        }
        browser.loadUrl(page)
        Toast.makeText(this, "官网播放 · 方向键移动光标，确定点击", Toast.LENGTH_LONG).show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val target = fullscreen ?: web ?: return super.dispatchKeyEvent(event)
        val isClick = event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
        val step = dp(if (event.repeatCount > 0) 40 else 30)
        val dx = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -step
            KeyEvent.KEYCODE_DPAD_RIGHT -> step
            else -> 0
        }
        val dy = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> -step
            KeyEvent.KEYCODE_DPAD_DOWN -> step
            else -> 0
        }
        if (!isClick && dx == 0 && dy == 0) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN || container.width <= 0 || container.height <= 0) return true
        if (cursorX.isNaN()) {
            cursorX = container.width / 2f
            cursorY = container.height / 2f
        }
        if (isClick) {
            if (event.repeatCount == 0) {
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val click = MotionEvent.obtain(time, time, action, cursorX, cursorY, 0)
                    click.source = InputDevice.SOURCE_TOUCHSCREEN
                    target.dispatchTouchEvent(click)
                    click.recycle()
                }
            }
        } else {
            val nextY = cursorY + dy
            if (fullscreen == null && (nextY < 1 || nextY >= container.height)) {
                web?.scrollBy(0, dy * 2)
            }
            cursorX = (cursorX + dx).coerceIn(1f, (container.width - 1).toFloat())
            cursorY = nextY.coerceIn(1f, (container.height - 1).toFloat())
        }
        cursor.translationX = cursorX - cursor.width / 2f
        cursor.translationY = cursorY - cursor.height / 2f
        cursor.visibility = View.VISIBLE
        cursor.removeCallbacks(hideCursor)
        cursor.postDelayed(hideCursor, 3_000)
        return true
    }

    @Deprecated("Handled by the TV Back button")
    override fun onBackPressed() {
        if (fullscreen != null) closeFullscreen() else finish()
    }

    override fun onPause() {
        web?.evaluateJavascript("document.querySelectorAll('video').forEach(video => video.pause())", null)
        web?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onDestroy() {
        if (::cursor.isInitialized) cursor.removeCallbacks(hideCursor)
        closeFullscreen()
        web?.let {
            container.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        web = null
        super.onDestroy()
    }

    private fun closeFullscreen() {
        val view = fullscreen ?: return
        fullscreen = null
        container.removeView(view)
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    companion object {
        private const val EXTRA_PAGE = "officialEpisodePage"

        internal fun intent(context: Context, page: String) =
            Intent(context, OfficialWebPlaybackActivity::class.java).putExtra(EXTRA_PAGE, page)
    }
}
