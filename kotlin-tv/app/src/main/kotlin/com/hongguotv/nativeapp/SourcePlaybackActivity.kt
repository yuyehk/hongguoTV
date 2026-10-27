// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.*
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hongguotv.core.SleepTimer

/** One decoder for explicit user sources. No background service or official account state. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SourcePlaybackActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val sleep = SleepTimer(SystemClock::elapsedRealtime)
    private lateinit var view: PlayerView
    private lateinit var label: TextView
    private var player: ExoPlayer? = null
    private var url = ""
    private var title = ""
    private var position = 0L
    private var autoplay = true
    private var lastSaved = 0L
    private var speed = 1f
    private var foreground = false
    private var playlist: SourcePlaylist? = null
    private var episodeIndex = 0
    private var episodeFinished = false
    private var transitioning = false
    private var sleepStopped = false
    private var pendingIntro = false
    private val skipBudget = SourceSkipBudget()
    private val rate = SourceReceiveRate()
    private val prefs by lazy { getSharedPreferences("native-source-progress-v1", MODE_PRIVATE) }
    private val sourceProgress by lazy { SourceProgressStore(this) }
    private var mediaSession: TvMediaSession? = null
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!foreground) return
            if (sleep.poll()) stopForSleep()
            val skips = playlist?.let { sourceProgress.skips(it.seriesKey) } ?: (0L to 0L)
            if (!episodeFinished && p.isPlaying && p.duration > 0 && skips.second > 0 && p.currentPosition >= SourcePlaybackPolicy.outroStart(skips.second, p.duration, skips.first)) finishEpisode()
            if (p.playbackState == Player.STATE_BUFFERING && !sleepStopped) label.text = "$title · 缓冲中 · ${rate.label()}"
            else if (p.isPlaying) label.text = title
            if (SystemClock.elapsedRealtime() - lastSaved >= 5000) save()
            updateSession(); handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        url = state?.getString("url") ?: intent.getStringExtra("url").orEmpty()
        if (!SourceMediaPolicy.valid(url)) { finish(); return }
        title = (state?.getString("title") ?: intent.getStringExtra("title")).orEmpty().take(200).ifBlank { "片源播放" }
        playlist = state?.getString("playlist")?.let(SourcePlaybackQueue::decode) ?: SourcePlaybackQueue.claim(url, title)
        episodeIndex = (state?.getInt("episodeIndex") ?: playlist?.index ?: 0).coerceIn(0, (playlist?.episodes?.lastIndex ?: 0))
        position = (state?.getLong("position") ?: playlist?.let { sourceProgress.positionOrNull(it.progressKey(episodeIndex)) }
            ?: prefs.getLong("position-" + SourceMediaPolicy.key(url), 0)).coerceAtLeast(0)
        autoplay = state?.getBoolean("autoplay") ?: true
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply {
            useController = true; controllerShowTimeoutMs = 4500
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING); setKeepContentOnPlayerReset(true)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        label = TextView(this).apply {
            text = title; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(24, 12, 24, 12); setBackgroundColor(0x88000000.toInt())
            isFocusable = true; setOnClickListener { menu() }; contentDescription = "播放菜单"
        }
        root.addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.TOP)); setContentView(root)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        mediaSession = TvMediaSession(this, ::requestSourcePlay, { if (foreground) player?.pause() },
            { if (foreground) player?.seekTo(it.coerceAtLeast(0)) }, { if (foreground) changeEpisode(it) }, { if (foreground) finish() })
    }

    private fun startPlayer() {
        if (player != null || !SourceMediaPolicy.valid(url)) return
        val transfer = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, spec: DataSpec, network: Boolean) { }
            override fun onTransferStart(source: DataSource, spec: DataSpec, network: Boolean) { }
            override fun onBytesTransferred(source: DataSource, spec: DataSpec, network: Boolean, bytes: Int) { if (network) rate.add(bytes) }
            override fun onTransferEnd(source: DataSource, spec: DataSpec, network: Boolean) { }
        }
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true).setUserAgent("HongguoTV/${BuildConfig.VERSION_NAME} AndroidTV").setTransferListener(transfer)
        val p = ExoPlayer.Builder(this)
            .setRenderersFactory(DefaultRenderersFactory(this).setEnableDecoderFallback(true).setMediaCodecSelector(DecoderPolicy.selector(this)))
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http)).build()
        player = p; view.player = p
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        p.setHandleAudioBecomingNoisy(true)
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { skipBudget.rendered() }
            override fun onPlayerError(error: PlaybackException) {
                val codec = error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                label.text = "$title · ${if (codec) "解码不兼容，可播放下一集或切换解码器" else "播放失败，可重试；网络错误不会自动跳集"}"
                android.util.Log.w("HongguoTV", "User-source playback error ${error.errorCodeName}")
                save(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (SourcePlaybackPolicy.canAutoSkip(p.playWhenReady,foreground,sleepStopped,error.errorCode) && hasEpisode(1) &&
                    skipBudget.allow(prefs.getBoolean("auto-codec-skip", false), codec)) transitionEpisode(1, true, true)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                updateSession()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && pendingIntro) {
                    pendingIntro = false
                    val intro = playlist?.let { sourceProgress.skips(it.seriesKey).first } ?: 0
                    val start = SourcePlaybackPolicy.introPosition(intro, p.duration)
                    if (start > p.currentPosition) p.seekTo(start)
                }
                if (state == Player.STATE_ENDED) finishEpisode(); updateSession()
            }
        })
        loadCurrent(p); handler.removeCallbacks(tick); handler.post(tick)
    }

    private fun loadCurrent(p: ExoPlayer) {
        pendingIntro = position == 0L
        episodeFinished = false; rate.reset()
        p.setMediaItem(MediaItem.fromUri(url)); p.seekTo(position)
        p.setPlaybackSpeed(speed); p.prepare(); p.playWhenReady = autoplay && foreground
        label.text = title
    }
    private fun hasEpisode(direction: Int): Boolean = playlist?.let { episodeIndex + direction in it.episodes.indices } == true
    private fun stopForSleep() {
        sleepStopped = true; autoplay = false; player?.pause(); label.text = "$title · 定时停止已生效"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun finishEpisode() {
        if (episodeFinished || transitioning) return
        episodeFinished = true; position = 0; save(completed = true)
        if (sleep.episodeEnded()) { stopForSleep(); return }
        if (foreground && !sleepStopped && hasEpisode(1)) changeEpisode(1, consumeSleep = false)
        else { player?.pause(); label.text = "$title · 本次播放已结束" }
    }
    /** Manual, codec and natural transitions share the foreground/sleep barrier. */
    private fun changeEpisode(direction: Int, consumeSleep: Boolean = true) = transitionEpisode(direction,consumeSleep,false)
    private fun transitionEpisode(direction:Int,consumeSleep:Boolean,requirePlaybackRequested:Boolean) {
        val list = playlist ?: return
        if (transitioning || !foreground || (requirePlaybackRequested && player?.playWhenReady!=true)) return
        if (sleep.poll() || (direction == 1 && consumeSleep && sleep.episodeEnded())) { stopForSleep(); return }
        val target = SourcePlaybackPolicy.target(episodeIndex, list.episodes.size, direction, foreground, sleepStopped) ?: return
        transitioning = true
        handler.post {
            try {
                if (!foreground || sleepStopped || sleep.poll()) { if (foreground) stopForSleep(); return@post }
                if(requirePlaybackRequested && player?.playWhenReady!=true)return@post
                save(completed = episodeFinished)
                episodeIndex = target; url = list.episodes[target].url; title = list.title(target)
                position = sourceProgress.positionOrNull(list.progressKey(target)) ?: prefs.getLong("position-" + SourceMediaPolicy.key(url), 0)
                autoplay = true
                ThirdStore(this).recordPlayback(list.source, list.detail.video, list.originalGroup, target + list.episodeOffset, list.episodes[target].label)
                player?.let(::loadCurrent); updateSession()
            } finally { transitioning = false }
        }
    }

    private fun save(completed: Boolean = false) {
        if (!SourceMediaPolicy.valid(url)) return
        val p = player
        position = SourceMediaPolicy.resumePosition(p?.currentPosition ?: position, completed || episodeFinished || p?.playbackState == Player.STATE_ENDED)
        playlist?.let { sourceProgress.save(it, episodeIndex, position) }
        val key = "position-" + SourceMediaPolicy.key(url)
        val edit = prefs.edit().putLong(key, position)
        val keys = prefs.getString("recent", "").orEmpty().split(',').filter { it.isNotBlank() && it != key }
        keys.drop(99).forEach { edit.remove(it) }
        edit.putString("recent", (listOf(key) + keys.take(99)).joinToString(",")).apply(); lastSaved = SystemClock.elapsedRealtime()
    }
    private fun updateSession() {
        if (!foreground) return
        val p = player ?: return
        val state = when { p.playbackState == Player.STATE_ENDED -> android.media.session.PlaybackState.STATE_STOPPED
            p.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
            p.playbackState == Player.STATE_BUFFERING -> android.media.session.PlaybackState.STATE_BUFFERING
            else -> android.media.session.PlaybackState.STATE_PAUSED }
        mediaSession?.update(title, episodeIndex, p.duration.coerceAtLeast(0), p.currentPosition.coerceAtLeast(0), p.playbackParameters.speed, state, hasEpisode(-1), hasEpisode(1))
    }
    private fun releasePlayer() {
        handler.removeCallbacks(tick); save(); view.player = null; player?.release(); player = null
        mediaSession?.deactivate(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun requestSourcePlay() {
        if (!foreground) return
        sleepStopped = false; autoplay = true
        player?.let { p ->
            if (p.playbackState == Player.STATE_ENDED || episodeFinished) { episodeFinished = false; p.seekTo(SourcePlaybackPolicy.introPosition(playlist?.let { sourceProgress.skips(it.seriesKey).first } ?: 0, p.duration)) }
            p.play()
        }
    }
    private fun skipsMenu() {
        val list = playlist ?: return
        val skips = sourceProgress.skips(list.seriesKey)
        AlertDialog.Builder(this).setTitle("${list.detail.video.name} · 跳过片头片尾")
            .setItems(arrayOf("片头 ${skips.first/1000} 秒", "片尾 ${skips.second/1000} 秒", "重置为不跳过")) { _, which ->
                if (which == 2) sourceProgress.setSkips(list, 0, 0) else {
                    val seconds = listOf(0L, 10L, 30L, 60L, 90L, 120L, 180L, 300L)
                    AlertDialog.Builder(this).setTitle(if (which == 0) "跳过片头" else "提前结束片尾")
                        .setItems(seconds.map { "$it 秒" }.toTypedArray()) { _, index -> sourceProgress.setSkips(list, if (which == 0) seconds[index]*1000 else skips.first, if (which == 1) seconds[index]*1000 else skips.second) }.show()
                }
            }.show()
    }
    private fun menu() {
        val p = player ?: return
        AlertDialog.Builder(this).setTitle(title).setItems(arrayOf("播放 / 暂停", "上一集${if (!hasEpisode(-1)) "（无）" else ""}", "下一集${if (!hasEpisode(1)) "（无）" else ""}",
            "倍速 ${speed}×", "画面比例", "解码：${DecoderPolicy.modeLabel(this)}", "定时：${sleep.label()}", "本剧片头 / 片尾", "解码失败自动跳集：${if (prefs.getBoolean("auto-codec-skip", false)) "开（最多连续5集）" else "关"}", "重新加载", "返回片源")) { _, index ->
            when (index) {
                0 -> if (p.isPlaying) p.pause() else requestSourcePlay()
                1 -> changeEpisode(-1)
                2 -> changeEpisode(1)
                3 -> AlertDialog.Builder(this).setTitle("倍速").setItems(arrayOf("0.75×", "1×", "1.25×", "1.5×", "2×")) { _, choice -> speed = listOf(.75f, 1f, 1.25f, 1.5f, 2f)[choice]; player?.setPlaybackSpeed(speed) }.show()
                4 -> { view.resizeMode = when (view.resizeMode) { AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT } }
                5 -> DecoderPolicy.showChooser(this) { autoplay = p.playWhenReady; releasePlayer(); startPlayer() }
                6 -> AlertDialog.Builder(this).setTitle("定时停止").setItems(arrayOf("关闭", "15 分钟", "30 分钟", "60 分钟", "90 分钟", "本集结束")) { _, choice -> sleepStopped = false; if (choice == 0) sleep.cancel() else if (choice == 5) sleep.afterEpisodes(1) else sleep.afterMinutes(listOf(15,30,60,90)[choice-1]) }.show()
                7 -> if (playlist != null) skipsMenu() else AlertDialog.Builder(this).setMessage("请从片源影片详情选择分集，以保存本剧片头片尾。").setPositiveButton("知道了", null).show()
                8 -> prefs.edit().putBoolean("auto-codec-skip", !prefs.getBoolean("auto-codec-skip", false)).apply()
                9 -> { autoplay = p.playWhenReady; releasePlayer(); startPlayer() }
                10 -> finish()
            }
        }.setNegativeButton("关闭", null).show()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) menu(); return true }
        return super.dispatchKeyEvent(event)
    }
    override fun onResume() { super.onResume(); foreground = true; if (::view.isInitialized) { startPlayer(); handler.removeCallbacks(tick); handler.post(tick) } }
    override fun onPause() { foreground = false; autoplay = false; handler.removeCallbacks(tick); player?.pause(); mediaSession?.deactivate(); save(); sleep.cancel(); super.onPause() }
    override fun onStop() { if (::view.isInitialized) releasePlayer(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        save(); outState.putLong("position", position); outState.putBoolean("autoplay", autoplay)
        outState.putString("url", url); outState.putString("title", title); outState.putInt("episodeIndex", episodeIndex)
        playlist?.let { outState.putString("playlist", SourcePlaybackQueue.encode(it)) }; super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); if (::view.isInitialized) releasePlayer(); mediaSession?.release(); super.onDestroy() }
}

internal class SourceReceiveRate(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private val samples = java.util.ArrayDeque<Pair<Long, Long>>()
    @Synchronized fun add(bytes: Int) {
        if (bytes <= 0) return
        val at = now(); val bucket = at / 100 * 100
        if (samples.isNotEmpty() && samples.last.first == bucket) { val last = samples.removeLast(); samples += bucket to (last.second + bytes) }
        else samples += bucket to bytes.toLong()
        trim(at)
    }
    private fun trim(at: Long) { while (samples.isNotEmpty() && (samples.first.first < at - 2000 || samples.size > 22)) samples.removeFirst() }
    @Synchronized fun reset() { samples.clear() }
    @Synchronized fun label(): String {
        val at = now(); trim(at)
        if (samples.isEmpty()) return "0 KB/s"
        val perSecond = samples.sumOf { it.second } * 1000 / (at - samples.first.first).coerceAtLeast(1000)
        return if (perSecond >= 1024 * 1024) String.format(java.util.Locale.ROOT, "%.1f MB/s", perSecond / 1048576.0) else "${perSecond/1024} KB/s"
    }
}
