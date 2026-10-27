// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import com.hongguotv.core.RelatedWork
import com.hongguotv.core.RelatedWorksPage

/** A remote-friendly, independently loaded strip below the episode grid. */
internal class RelatedWorksView(
    context: Context,
    private val upper: List<View>,
    private val loadCover: (ImageView, String) -> Unit,
    private val reserveLabel: (RelatedWork) -> String,
    private val open: (RelatedWork, View) -> Unit,
    private val reload: () -> Unit,
    private val loadMore: (Int) -> Unit = {},
) : LinearLayout(context) {
    private var refreshButton: TextView? = null
    private var moreButton: TextView? = null
    private val labels = mutableListOf<Pair<RelatedWork, TextView>>()

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float = 15f) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(TvStyle.text)
            includeFontPadding = false
        }

    init {
        orientation = VERTICAL
        setPadding(0, dp(14), 0, dp(12))
        loading()
    }

    private fun reset(status: String) {
        removeAllViews()
        labels.clear()
        moreButton = null
        val heading =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        heading.addView(label("相关作品", 22f), LayoutParams(0, -2, 1f))
        val refresh =
            label("刷新", 15f).apply {
                id = View.generateViewId()
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = TvStyle.card(context, false)
                setOnFocusChangeListener { view, focused ->
                    view.background = TvStyle.card(context, focused)
                }
                setOnClickListener { reload() }
                nextFocusUpId = upper.lastOrNull()?.id ?: id
            }
        refreshButton = refresh
        heading.addView(refresh)
        addView(heading)
        if (status.isNotBlank())
            addView(
                label(status, 13f).apply {
                    setTextColor(TvStyle.muted)
                    setPadding(0, dp(8), 0, dp(8))
                }
            )
        upper.takeLast(10).forEach { it.nextFocusDownId = refresh.id }
    }

    fun loading() {
        reset("正在加载相关作品…")
    }

    fun failed() {
        reset("相关作品暂时无法加载，可按刷新重试；当前剧集仍可正常观看。")
    }

    fun loadingMore() {
        moreButton?.apply { text = "正在加载…"; isEnabled = false }
    }

    fun show(page: RelatedWorksPage) = render(page.items, page.message, page.nextPage)

    fun show(rows: List<RelatedWork>) = render(rows,
        if (rows.isEmpty()) "暂无相关剧集" else "左右选择其他季；待上线作品可预约", null)

    private fun render(rows: List<RelatedWork>, message: String, nextPage: Int?) {
        reset(message)
        nextPage?.let { page ->
            val more = label("加载更多", 15f).apply {
                id = View.generateViewId()
                tag = "related-load-more"
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = TvStyle.card(context, false)
                setOnFocusChangeListener { view, focused -> view.background = TvStyle.card(context, focused) }
                setOnClickListener { loadMore(page) }
                nextFocusLeftId = refreshButton?.id ?: id
                nextFocusUpId = upper.lastOrNull()?.id ?: id
            }
            moreButton = more
            (getChildAt(0) as LinearLayout).addView(more)
            refreshButton?.nextFocusRightId = more.id
        }
        if (rows.isEmpty()) return
        val scroll =
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
            }
        val line = LinearLayout(context).apply { orientation = HORIZONTAL }
        scroll.addView(line)
        addView(scroll)
        val cards =
            rows.map { work ->
                val card =
                    LinearLayout(context).apply {
                        orientation = VERTICAL
                        id = View.generateViewId()
                        tag = work.series.id
                        isFocusable = true
                        isFocusableInTouchMode = true
                        contentDescription = work.series.title + "，" + work.label +
                            if (work.confirmed) "" else "，搜索匹配，未确认同一系列，手动查看"
                        setPadding(dp(5), dp(5), dp(5), dp(9))
                        background = TvStyle.card(context, false)
                    }
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                card.addView(image, LayoutParams(-1, dp(176)))
                loadCover(image, work.series.cover)
                card.addView(
                    label(work.series.title, 14f).apply {
                        maxLines = 2
                        minLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                    LayoutParams(-1, -2),
                )
                card.addView(label(work.season, 12f))
                val status = label(when {
                    !work.confirmed -> "搜索匹配 · 未确认 · 手动查看"
                    work.upcoming -> reserveLabel(work)
                    else -> "已上线 · 查看剧集"
                }, 12f)
                card.addView(status)
                labels += work to status
                card.setOnClickListener { open(work, card) }
                card.setOnFocusChangeListener { view, focused ->
                    view.background = TvStyle.card(context, focused)
                    if (focused) {
                        loadCover(image, work.series.cover)
                        view.requestRectangleOnScreen(
                            android.graphics.Rect(0, 0, view.width, view.height),
                            false,
                        )
                    }
                }
                line.addView(card, LayoutParams(dp(142), -2).apply { rightMargin = dp(12) })
                card
            }
        cards.forEachIndexed { index, card ->
            card.nextFocusLeftId = cards.getOrNull(index - 1)?.id ?: card.id
            card.nextFocusRightId = cards.getOrNull(index + 1)?.id ?: card.id
            card.nextFocusUpId = refreshButton?.id ?: upper.lastOrNull()?.id ?: card.id
            card.nextFocusDownId = card.id
        }
        refreshButton?.nextFocusDownId = cards.first().id
        moreButton?.nextFocusDownId = cards.last().id
        upper.takeLast(10).forEachIndexed { index, view ->
            view.nextFocusDownId = cards[minOf(index, cards.lastIndex)].id
        }
    }

    fun refreshLabels() {
        labels.forEach { (work, view) -> if (work.upcoming) view.text = reserveLabel(work) }
    }
}
