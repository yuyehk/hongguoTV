// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Dialog
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.media3.common.Player
import com.hongguotv.core.*
import java.util.concurrent.Executors
import org.json.JSONObject

/** Owns only anonymous social reads. Playback and account writes are independent. */
class TvSocial(
    private val activity: Activity,
    private val player: () -> Player?,
    private val identity: () -> Pair<String, String>?,
    private val readComments: (String, String, String) -> JSONObject = AppCommentPages()::read,
    private val readReviews: (String, String) -> JSONObject =
        PublicComments(okhttp3.OkHttpClient())::read,
) {
    private val emoji = CommentEmoji(activity)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val timed = TimedComments()
    private val prefs = activity.getSharedPreferences("social", 0)
    private val options = SocialPreferences(activity)
    private var active = false
    private var key: Pair<String, String>? = null
    private var scope: RequestScope? = null
    private var commentScope: RequestScope? = null
    private var generation = 0
    private var dialog: Dialog? = null
    private var settingsDialog: Dialog? = null
    private var keywordDialog: android.app.AlertDialog? = null
    private var overlay: BulletView? = null
    private var loading = false
    private var failedAt = 0L
    private val pages = linkedMapOf<Long, JSONObject>()

    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()

    private val tick =
        object : Runnable {
            override fun run() {
                if (!active || !options.enabled) return
                val next = identity()
                if (!options.isEnabled(next?.first)) return
                if (next != key) {
                    clearData()
                    key = next
                }
                val p = player()
                overlay?.running = p?.isPlaying == true
                overlay?.invalidate()
                if (
                    p != null &&
                        next != null &&
                        options.isEnabled(next.first) &&
                        !loading &&
                        p.duration in 1..86400000 &&
                        p.currentPosition < p.duration &&
                        (failedAt == 0L ||
                            android.os.SystemClock.elapsedRealtime() - failedAt > 15000)
                ) {
                    val start = p.currentPosition / 30000 * 30000
                    val desired =
                        listOf(
                            start,
                            if (start > 0 && p.currentPosition - start < 15000) start - 30000
                            else start,
                            if (p.currentPosition - start > 20000 && start + 30000 < p.duration)
                                start + 30000
                            else start,
                        )
                    desired
                        .firstOrNull { !pages.containsKey(it) }
                        ?.let { loadTimed(next, it, p.duration) }
                }
                main.postDelayed(this, 500)
            }
        }

    fun attach(root: android.widget.FrameLayout) {
        overlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        overlay = BulletView().also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    }

    fun foreground(value: Boolean) {
        active = value
        main.removeCallbacks(tick)
        if (value && options.isEnabled(identity()?.first)) main.post(tick)
        else {
            reset()
            keywordDialog?.dismiss(); keywordDialog = null
            settingsDialog?.dismiss(); settingsDialog = null
            overlay?.running = false
            overlay?.invalidate()
        }
    }

    fun reset() {
        main.removeCallbacks(tick)
        clearData()
        if (active && options.isEnabled(identity()?.first)) main.post(tick)
    }

    private fun clearData() {
        generation++
        scope?.cancel()
        scope = null
        commentScope?.cancel()
        commentScope = null
        loading = false
        failedAt = 0
        pages.clear()
        overlay?.setRows(emptyList())
        dialog?.dismiss()
        dialog = null
        key = null
    }

    fun destroy() {
        foreground(false)
        worker.shutdownNow()
    }

    private fun loadTimed(ids: Pair<String, String>, start: Long, duration: Long) {
        loading = true
        val ticket = generation
        val request = RequestScope()
        scope = request
        worker.submit {
            try {
                val page = request.run { timed.read(ids.first, ids.second, start, duration) }
                main.post {
                    if (ticket != generation || !active || !options.isEnabled(ids.first)) return@post
                    loading = false
                    pages[start] = page
                    while (pages.size > 6) pages.remove(
                        pages.keys.maxBy { kotlin.math.abs(it - start) }
                    )
                    val rows =
                        pages.values.flatMap { value ->
                            val a = value.getJSONArray("items")
                            (0 until a.length()).map { a.getJSONObject(it) }
                        }
                    overlay?.setRows(rows)
                }
            } catch (_: Exception) {
                main.post {
                    if (ticket == generation) {
                        loading = false
                        failedAt = android.os.SystemClock.elapsedRealtime()
                    }
                }
            }
        }
    }

    private fun label(text: String, size: Float = 18f) =
        TextView(activity).apply {
            this.text = text
            textSize = size
            setTextColor(TvStyle.text)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }

    private fun button(text: String, action: () -> Unit) =
        label(text).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            minHeight = dp(48)
            background = TvStyle.card(activity, false)
            setOnFocusChangeListener { _, focus -> background = TvStyle.card(activity, focus) }
            setOnClickListener { action() }
        }

    fun settings(anchor: View) {
        settingsDialog?.dismiss()
        val d = Dialog(activity)
        settingsDialog = d
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                setBackgroundColor(TvStyle.background)
            }
        body.addView(label("弹幕设置", 22f))
        fun option(title: () -> String, action: () -> Unit) {
            lateinit var b: TextView
            b =
                button(title()) {
                    action()
                    b.text = title()
                    (0 until body.childCount).map { body.getChildAt(it) }.filterIsInstance<SeekBar>()
                        .firstOrNull()?.progress = options.opacity - 21
                    overlay?.setRows(
                        pages.values.flatMap {
                            val a = it.getJSONArray("items")
                            (0 until a.length()).map { i -> a.getJSONObject(i) }
                        }
                    )
                    this@TvSocial.overlay?.invalidate()
                }
            body.addView(b)
        }
        option({ "弹幕：${if(options.enabled) "开" else "关"}" }) {
            options.enabled = !options.enabled
            reset()
        }
        val series = identity()?.first
        if (series != null) option({ "本剧弹幕：${if(options.seriesEnabled(series)) "开" else "关"}" }) {
            options.setSeriesEnabled(series, !options.seriesEnabled(series))
            reset()
        }
        option({ "不透明度：${options.opacity}%" }) {
            val steps = listOf(100, 80, 60, 40, 21)
            options.opacity = steps[(steps.indexOf(options.opacity).coerceAtLeast(0) + 1) % steps.size]
        }
        body.addView(SeekBar(activity).apply {
            max = 79
            keyProgressIncrement = 1
            progress = options.opacity - 21
            isFocusable = true
            contentDescription = "弹幕不透明度，21 到 100 百分比，左右方向键调整"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    options.opacity = progress + 21
                    (0 until body.childCount).map { body.getChildAt(it) }.filterIsInstance<TextView>()
                        .firstOrNull { it.text.startsWith("不透明度：") }?.text = "不透明度：${options.opacity}%"
                    this@TvSocial.overlay?.invalidate()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })
        option({ "屏蔽词：${options.keywords.size} 个" }) {
            val input = EditText(activity).apply {
                setText(options.keywords.joinToString("\n"))
                hint = "每行一个，最多 50 个；按文字匹配"
                filters = arrayOf(android.text.InputFilter.LengthFilter(4000))
                minLines = 3
                maxLines = 6
            }
            keywordDialog = android.app.AlertDialog.Builder(activity).setTitle("屏蔽弹幕关键词")
                .setView(input).setNegativeButton("取消", null)
                .setPositiveButton("保存") { _, _ ->
                    options.keywords = SocialPolicy.keywords(input.text.toString())
                    overlay?.setRows(pages.values.flatMap { page ->
                        val a = page.getJSONArray("items")
                        (0 until a.length()).map { a.getJSONObject(it) }
                    })
                    d.dismiss()
                    settings(anchor)
                }.show()
        }
        option({ "字号：${prefs.getInt("font",24)}" }) {
            val n = prefs.getInt("font", 24)
            prefs.edit().putInt("font", if (n >= 32) 20 else n + 4).apply()
        }
        option({ "密度：${prefs.getInt("lanes",2)} 行" }) {
            prefs.edit().putInt("lanes", prefs.getInt("lanes", 2) % 4 + 1).apply()
        }
        option({ "速度：${if(prefs.getInt("travel",12000)==12000) "慢速" else "标准"}" }) {
            prefs
                .edit()
                .putInt("travel", if (prefs.getInt("travel", 12000) == 12000) 9000 else 12000)
                .apply()
        }
        body.addView(
            button("重新读取弹幕") {
                reset()
                d.dismiss()
            }
        )
        body.addView(label("真实时间轴弹幕；无内容时不使用普通评论代替。", 14f))
        body.addView(button("关闭") { d.dismiss() })
        d.setContentView(ScrollView(activity).apply { addView(body) })
        d.setOnDismissListener {
            if (settingsDialog === d) settingsDialog = null
            if (anchor.isAttachedToWindow) anchor.requestFocus()
        }
        d.show()
        d.window?.setLayout(dp(500), minOf(dp(640), (activity.resources.displayMetrics.heightPixels * .9).toInt()))
        body.getChildAt(1).requestFocus()
    }

    fun showComments(anchor: View, target: Pair<String, String>? = null) {
        val ids = target ?: identity() ?: return
        dialog?.dismiss()
        commentScope?.cancel()
        key = ids
        val ticket = ++generation
        // The timed read belongs to the old generation as well.
        scope?.cancel()
        loading = false
        val d = Dialog(activity)
        dialog = d
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var cursor = ""
        var more = true
        var busy = false
        var reviewMode = false
        var disposed = false
        var pageGeneration = 0
        val seen = mutableSetOf<String>()
        val cursors = mutableSetOf<String>()
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundColor(TvStyle.background)
            }
        body.addView(label("评论与剧评", 22f))
        val tabs = LinearLayout(activity)
        body.addView(tabs)
        val status = label("匿名读取 · 点赞数量只读", 14f)
        body.addView(status)
        val scroll = ScrollView(activity)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        lateinit var load: TextView
        lateinit var read: () -> Unit
        fun addRow(row: JSONObject) {
            val created = row.optLong("created")
            val date =
                if (created > 0)
                    java.text
                        .SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                        .format(java.util.Date(created * 1000))
                else ""
            val author = row.optString("author").ifBlank { "红果用户" }
            val content = row.optString("content")
            val card =
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setPadding(dp(12), dp(10), dp(12), dp(12))
                    background = TvStyle.card(activity, false)
                    contentDescription = "$author，$content"
                    setOnFocusChangeListener { _, focused ->
                        background = TvStyle.card(activity, focused)
                    }
                }
            card.addView(
                label("$author  ·  赞 ${row.optLong("likes")}  ·  $date", 13f).apply {
                    setTextColor(TvStyle.muted)
                    setPadding(0, 0, 0, dp(8))
                }
            )
            card.addView(
                label("", 20f).apply {
                    text = emoji.render(content, textSize * 1.2f)
                    setPadding(0, 0, 0, 0)
                    setLineSpacing(dp(6).toFloat(), 1f)
                }
            )
            card.setOnClickListener {
                val expanded =
                    android.app.AlertDialog.Builder(activity)
                        .setTitle(author)
                        .setMessage(emoji.render(content, dp(24).toFloat()))
                        .setPositiveButton("关闭", null)
                        .create()
                expanded.setOnDismissListener { if (card.isAttachedToWindow) card.requestFocus() }
                expanded.show()
            }
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        read = {
            if (!busy && more && !disposed) {
                busy = true
                load.text = "正在读取…"
                val sent = cursor
                val mode = reviewMode
                val batch = pageGeneration
                val request = RequestScope()
                commentScope = request
                worker.submit {
                    try {
                        val page =
                            request.run {
                                if (mode)
                                    readReviews(ids.first, ids.second).getJSONObject("reviews")
                                else readComments(ids.first, ids.second, sent)
                            }
                        main.post {
                            if (
                                disposed ||
                                    ticket != generation ||
                                    batch != pageGeneration ||
                                    mode != reviewMode
                            )
                                return@post
                            busy = false
                            val previousScroll = scroll.scrollY
                            val rows = page.getJSONArray("items")
                            for (i in 0 until rows.length()) {
                                val row = rows.getJSONObject(i)
                                if (seen.add(row.getString("id"))) addRow(row)
                            }
                            scroll.post { if (!disposed) scroll.scrollTo(0, previousScroll) }
                            more = !mode && page.getBoolean("hasMore")
                            if (more) {
                                val next = page.getString("nextCursor")
                                if (!cursors.add(next)) {
                                    more = false
                                    status.text = "游标重复，停止自动加载；可重新打开重试。"
                                }
                                cursor = next
                            }
                            status.text =
                                if (mode) "官网节选 ${seen.size} 条 / 共 ${page.optLong("total")} 条"
                                else "已加载 ${seen.size} 条 / 共 ${page.optLong("total")} 条"
                            load.text = if (more) "加载更多评论" else if (mode) "官网剧评节选已读完" else "已到末尾"
                        }
                    } catch (_: Exception) {
                        main.post {
                            if (
                                !disposed &&
                                    ticket == generation &&
                                    batch == pageGeneration &&
                                    mode == reviewMode
                            ) {
                                busy = false
                                load.text = "加载失败，按确认重试"
                                status.text = "保留已有评论，重试使用原游标"
                            }
                        }
                    }
                }
            }
        }
        fun select(mode: Boolean) {
            pageGeneration++
            commentScope?.cancel()
            reviewMode = mode
            busy = false
            cursor = ""
            more = true
            seen.clear()
            cursors.clear()
            list.removeAllViews()
            read()
        }
        tabs.addView(button("本集评论") { select(false) }, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(button("整剧剧评") { select(true) }, LinearLayout.LayoutParams(0, -2, 1f))
        load = button("加载更多评论") { read() }
        body.addView(load)
        body.addView(label("上下浏览，到底继续加载。发送、回复未接入。", 13f))
        body.addView(button("关闭") { d.dismiss() })
        scroll.setOnScrollChangeListener { _: View, _: Int, y: Int, _: Int, _: Int ->
            if (!reviewMode && y > 0 && y + scroll.height >= list.height - dp(80)) read()
        }
        d.setContentView(body)
        d.setOnDismissListener {
            disposed = true
            commentScope?.cancel()
            if (dialog === d) dialog = null
            if (anchor.isAttachedToWindow) anchor.requestFocus()
        }
        d.show()
        d.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.END)
            setDimAmount(0f)
            setLayout(
                minOf(dp(500), (activity.resources.displayMetrics.widthPixels * .65).toInt()),
                -1,
            )
        }
        tabs.getChildAt(0).requestFocus()
        read()
    }

    private inner class BulletView : View(activity) {
        var running = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
        private var rows = emptyList<Triple<String, Long, Int>>()

        fun setRows(values: List<JSONObject>) {
            val lanes = prefs.getInt("lanes", 2).coerceIn(1, 4)
            val travel = prefs.getInt("travel", 12000).coerceIn(6000, 20000)
            val blocked = options.keywords
            val free = LongArray(lanes)
            val seen = mutableSetOf<String>()
            rows =
                values
                    .sortedBy { it.getLong("timeMS") }
                    .mapNotNull {
                        val at = it.getLong("timeMS")
                        val lane = free.indexOfFirst { t -> t <= at }
                        if (lane < 0 || !seen.add(it.getString("id")) || SocialPolicy.blocked(it.getString("text"), blocked)) null
                        else {
                            free[lane] = at + travel
                            Triple(it.getString("text"), at, lane)
                        }
                    }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!active || !options.isEnabled(identity()?.first)) return
            val p = player() ?: return
            val now = p.currentPosition
            val travel = prefs.getInt("travel", 12000).coerceIn(6000, 20000)
            paint.textSize = dp(prefs.getInt("font", 24).coerceIn(16, 40)).toFloat()
            for ((text, at, lane) in rows) if (now >= at && now < at + travel) {
                val x = width - (width + paint.measureText(text)) * (now - at).toFloat() / travel
                val y = dp(65) + lane * (paint.textSize + dp(12))
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                paint.color = Color.BLACK
                paint.alpha = SocialPolicy.alpha(options.opacity)
                canvas.drawText(text, x, y, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                paint.alpha = SocialPolicy.alpha(options.opacity)
                canvas.drawText(text, x, y, paint)
            }
            if (running && isAttachedToWindow) postInvalidateOnAnimation()
        }
    }
}
