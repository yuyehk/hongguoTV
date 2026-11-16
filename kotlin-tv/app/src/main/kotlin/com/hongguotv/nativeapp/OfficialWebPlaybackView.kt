// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.hongguotv.core.EmptyContentResponse
import java.net.URI

private const val OFFICIAL_PLAYBACK_HOST = "hongguoduanju.com"
private val episodeIdPattern = Regex("[0-9]{1,30}")

internal fun officialEpisodePage(seriesId: String, episodeId: String?): String? {
    if (!episodeIdPattern.matches(seriesId) ||
        episodeId == null || !episodeIdPattern.matches(episodeId)
    ) return null
    return "https://$OFFICIAL_PLAYBACK_HOST/player/$seriesId/$episodeId"
}

internal fun officialPlaybackFallback(problem: Throwable, seriesId: String, episodeId: String?): String? =
    if (problem is EmptyContentResponse) officialEpisodePage(seriesId, episodeId) else null

internal fun isOfficialPlaybackNavigation(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals(OFFICIAL_PLAYBACK_HOST, ignoreCase = true) &&
        uri.userInfo == null && uri.port in listOf(-1, 443)
}

/** The official player and its remote cursor inside the current playback screen. */
@SuppressLint("SetJavaScriptEnabled")
internal class OfficialWebPlaybackView(context: Context, page: String) : FrameLayout(context) {
    private val cursor: View
    private val web: WebView
    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var cursorX = Float.NaN
    private var cursorY = Float.NaN
    private val hideCursor = Runnable { hideCursorView() }

    init {
        require(isOfficialPlaybackNavigation(page))
        val browser = WebView(context)
        web = browser
        setBackgroundColor(Color.BLACK)
        addView(browser, LayoutParams(-1, -1))
        cursor = View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(2), Color.BLACK)
            }
            visibility = View.INVISIBLE
        }
        addView(cursor, LayoutParams(dp(16), dp(16)))
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
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
                addView(view, LayoutParams(-1, -1))
                cursor.bringToFront()
            }

            override fun onHideCustomView() { closeFullscreen() }
        }
        browser.loadUrl(page)
    }

    fun handleRemoteKey(event: KeyEvent): Boolean {
        val target = fullscreen ?: web
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
        if (!isClick && dx == 0 && dy == 0) return false
        if (event.action != KeyEvent.ACTION_DOWN || width < 2 || height < 2) return true
        if (cursorX.isNaN()) {
            cursorX = width / 2f
            cursorY = height / 2f
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
            if (fullscreen == null && (nextY < 1 || nextY >= height)) {
                web.scrollBy(0, dy * 2)
            }
            cursorX = (cursorX + dx).coerceIn(1f, (width - 1).toFloat())
            cursorY = nextY.coerceIn(1f, (height - 1).toFloat())
        }
        cursor.translationX = cursorX - cursor.width / 2f
        cursor.translationY = cursorY - cursor.height / 2f
        cursor.visibility = View.VISIBLE
        cursor.removeCallbacks(hideCursor)
        cursor.postDelayed(hideCursor, 3_000)
        return true
    }

    fun pauseMedia() {
        web.evaluateJavascript("document.querySelectorAll('video').forEach(video => video.pause())", null)
    }

    fun pause() {
        pauseMedia()
        web.onPause()
    }

    fun resume() = web.onResume()

    fun release() {
        cursor.removeCallbacks(hideCursor)
        closeFullscreen()
        web.stopLoading()
        removeView(web)
        web.destroy()
    }

    fun closeFullscreen(): Boolean {
        val view = fullscreen ?: return false
        fullscreen = null
        removeView(view)
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        return true
    }

    private fun hideCursorView() {
        cursor.visibility = View.INVISIBLE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}
