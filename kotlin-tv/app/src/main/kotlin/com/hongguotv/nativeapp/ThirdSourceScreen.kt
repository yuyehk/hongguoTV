// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.UUID
import java.util.concurrent.Executors

/** D-pad native dialogs for explicit, empty-by-default MacCMS sources. No independent player. */
class ThirdSourceScreen(private val activity: Activity, private val onPlay: (String, String) -> Unit) {
    val store = ThirdStore(activity)
    private val main = Handler(Looper.getMainLooper())
    private val worker by lazy { Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-user-source") } }
    private val client by lazy { ThirdCmsClient() }
    private var workerStarted = false
    private var dialog: AlertDialog? = null
    private var generation = 0
    private var destroyed = false
    private var pending: java.util.concurrent.Future<*>? = null
    private var importServer: SourceImportServer? = null
    private val categories = mutableMapOf<String, List<ThirdCategory>>()
    val showing get() = dialog?.isShowing == true
    private fun open(next: AlertDialog) {
        if (destroyed || activity.isFinishing || activity.isDestroyed) return
        importServer?.close(); importServer = null
        dialog?.dismiss(); dialog = next
        next.setOnDismissListener { if (dialog === next) { dialog = null; generation++; pending?.cancel(true); importServer?.close(); importServer = null; if (workerStarted) client.cancel() } }
        next.show(); next.listView?.requestFocus()
    }
    private fun choose(title: String, labels: List<String>, action: (Int) -> Unit) =
        open(AlertDialog.Builder(activity).setTitle(title).setItems(labels.toTypedArray()) { _, index -> action(index) }
            .setNegativeButton("关闭", null).create())
    private fun info(title: String, text: String, next: (() -> Unit)? = null) =
        open(AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton("知道了") { _, _ -> next?.invoke() }.create())
    fun close() { generation++; importServer?.close(); importServer = null; dialog?.dismiss(); dialog = null; pending?.cancel(true); if (workerStarted) client.cancel() }
    fun destroy() { close(); destroyed = true; if (workerStarted) { worker.shutdownNow(); client.close() } }

    fun show() {
        val sources = store.orderedSources()
        val current = store.currentId()
        val labels = listOf("添加 / 导入 / 删除片源", "片源内收藏（${store.favorites().size}）", "最近选播（${store.history().size}）", "聚合搜索全部片源") +
            sources.map { (if (it.id == current) "✓ " else "") + it.name + (if (it.id == store.preferredId()) " · 手动优先" else store.measurements()[it.id]?.let { m -> if (m.success) " · ${m.elapsedMs}ms" else " · 上次未响应" }.orEmpty()) }
        choose(if (sources.isEmpty()) "自定义片源 · 尚未添加" else "自定义片源 · 选择切换", labels) { index ->
            when (index) { 0 -> manage(); 1 -> favorites(); 2 -> history(); 3 -> aggregateInput(); else -> sources.getOrNull(index - 4)?.let { store.setCurrent(it.id); browse(it) } }
        }
    }
    private fun manage() = choose("用户片源 · 仅支持 MacCMS JSON", listOf("添加接口地址", "粘贴 JSON 配置（sites / sources 数组）", "手机扫码导入配置", "设置优先片源（覆盖实测排序）", "删除片源", "返回片源列表")) { index ->
        when (index) { 0 -> add(); 1 -> importConfig(); 2 -> phoneImport(); 3 -> priority(); 4 -> remove(); else -> show() }
    }
    private fun priority() {
        val sources = store.sources()
        choose("优先片源 · 速度由主动请求实测", listOf("自动：成功响应优先，再按接口延迟") + sources.map { it.name }) { index ->
            store.setPreferred(sources.getOrNull(index - 1)?.id); show()
        }
    }
    private fun phoneImport() {
        val addresses = runCatching { LanInputAddresses.find(activity.getSystemService(ConnectivityManager::class.java)) }.getOrDefault(emptyList())
        if (addresses.isEmpty()) { info("手机导入", "未找到可用局域网地址，请先连接 Wi-Fi 或有线网络。", ::manage); return }
        val status = TextView(activity).apply { text = "手机与电视连接同一局域网，扫码后粘贴 JSON。入口最多开放五分钟，关闭弹窗或应用退到后台立即停止。仅支持普通 MacCMS，脚本/JAR/加密规则不支持。" }
        val server = runCatching { SourceImportServer { raw ->
            val incoming = ThirdCms.parseSources(raw)
            val count = synchronized(store) { store.mergeSources(incoming) }
            main.post { if (!destroyed && importServer != null) status.text = "识别 ${incoming.size} 个兼容接口，新增 $count 个。关闭弹窗后可选择播放。" }
            count
        } }.getOrElse { info("手机导入不可用", "无法打开临时局域网入口，可使用粘贴配置。", ::manage); return }
        val url = "http://${addresses.first().address}:${server.port}/${server.nonce}"
        val content = column(); content.addView(status)
        val bits = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 360, 360)
        content.addView(ImageView(activity).apply {
            setImageBitmap(Bitmap.createBitmap(IntArray(360 * 360) { if (bits[it % 360, it / 360]) Color.BLACK else Color.WHITE }, 360, 360, Bitmap.Config.ARGB_8888))
            contentDescription = "手机片源导入二维码"
        }, LinearLayout.LayoutParams(360, 360))
        content.addView(TextView(activity).apply { text = url; textSize = 12f })
        val next = AlertDialog.Builder(activity).setTitle("手机扫码导入片源").setView(content).setNegativeButton("关闭入口") { _, _ -> show() }.create()
        open(next); importServer = server
    }
    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (20 * resources.displayMetrics.density).toInt(); setPadding(padding, 0, padding, 0)
    }
    private fun input(hint: String, multiline: Boolean = false) = EditText(activity).apply {
        this.hint = hint; textSize = 16f; maxLines = if (multiline) 6 else 1
        inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine(!multiline)
    }
    private fun add() {
        val content = column(); val name = input("片源名称"); val api = input("https://服务器/api.php/provide/vod/")
        val status = TextView(activity).apply { text = "仅使用你有权访问的 HTTP(S) MacCMS 接口。不会预置、扫描或自动探测其他片源。" }
        content.addView(status); content.addView(name); content.addView(api)
        val next = AlertDialog.Builder(activity).setTitle("添加自定义片源").setView(content).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        open(next)
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = api.text.toString().trim(); val label = name.text.toString().trim().take(100)
            when {
                !ThirdCms.validApi(url) -> status.text = "请输入完整 HTTP(S) JSON 接口地址，不支持脚本/JAR、URL 用户名密码或 # 片段。"
                label.isEmpty() -> status.text = "请输入片源名称。"
                else -> {
                    val source = ThirdSource(UUID.nameUUIDFromBytes(url.toByteArray(Charsets.UTF_8)).toString(), label, url)
                    val added = store.mergeSources(listOf(source))
                    info("保存片源", if (added > 0) "已保存。选择片源后才会请求内容。" else "接口已存在或已达到 30 个片源上限。", ::show)
                }
            }
        }
        name.requestFocus()
    }
    private fun importConfig() {
        val content = column(); val entry = input("粘贴 JSON 配置", true)
        val status = TextView(activity).apply { text = "只读取 type=1 / 普通 MacCMS 接口；跳过脚本、jar 与加密源。只追加，不覆盖已有片源。" }
        content.addView(status); content.addView(entry)
        val next = AlertDialog.Builder(activity).setTitle("导入片源配置").setView(content).setNegativeButton("取消", null).setPositiveButton("检查导入", null).create()
        open(next)
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching { ThirdCms.parseSources(entry.text.toString()) }.onSuccess { incoming ->
                val count = store.mergeSources(incoming)
                info("导入结果", "识别 ${incoming.size} 个兼容接口，新增 $count 个。接口地址只保存在电视本机，不进入红果记录备份。", ::show)
            }.onFailure { status.text = "无法识别该配置。请检查 JSON 格式及 sites / sources 数组（最多 2 MB）。" }
        }
        entry.requestFocus()
    }
    private fun remove() {
        val sources = store.sources()
        if (sources.isEmpty()) { info("删除片源", "暂无片源。", ::show); return }
        choose("删除哪个片源", sources.map { it.name }) { index ->
            val source = sources[index]
            val next = AlertDialog.Builder(activity).setTitle("删除 ${source.name}？")
                .setMessage("该片源、独立收藏及最近选播记录会从本机删除。红果收藏和观看记录不受影响。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> store.removeSource(source.id); show() }.create()
            open(next); next.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
        }
    }
    private fun favorites() {
        val rows = store.favorites(); val sources = store.sources().associateBy { it.id }
        if (rows.isEmpty()) { info("片源内收藏", "暂无收藏。打开片源影片详情后可收藏；它与红果收藏分别保存。", ::show); return }
        choose("片源内收藏", rows.map { "${it.video.name} · ${sources[it.sourceId]?.name ?: "源已删除"}" }) { index ->
            val row = rows[index]; val source = sources[row.sourceId]
            if (source == null) info("片源不可用", "请先重新添加相应接口。", ::show)
            else detail(source, row.video) { favorites() }
        }
    }
    private fun history() {
        val rows = store.history(); val sources = store.sources().associateBy { it.id }
        if (rows.isEmpty()) { info("最近选播", "暂无记录。选择片源影片播放后，会在这里保留集数入口；实际播放位置由独立片源播放器保存在本机。", ::show); return }
        choose("最近选播 · 本机独立记录", rows.map { "${it.video.name} · ${it.episodeLabel} · ${sources[it.sourceId]?.name ?: "源已删除"}" }) { index ->
            val row = rows[index]; val source = sources[row.sourceId]
            if (source == null) info("片源不可用", "请重新添加相应接口。", ::show) else detail(source, row.video) { history() }
        }
    }
    private fun aggregateInput() {
        if (store.sources().isEmpty()) { info("聚合搜索", "请先添加 MacCMS 片源。", ::show); return }
        val entry = input("输入片名")
        val next = AlertDialog.Builder(activity).setTitle("聚合搜索 · 最多并发 3 源 / 20 秒").setView(entry)
            .setNegativeButton("取消", null).setPositiveButton("搜索", null).create()
        open(next); entry.requestFocus()
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val query = entry.text.toString().trim().take(100)
            if (query.isBlank()) entry.error = "请输入片名" else aggregate(query)
        }
    }
    private fun aggregate(query: String, prior: ThirdAggregateResult? = null) {
        val sources = store.orderedSources().filter { prior == null || it.id in prior.nextSourceIds }
        val page = (prior?.page ?: 0) + 1
        request("全部片源搜索 · $query · 第 $page 页", prior?.let { { aggregateRows(query, it) } } ?: ::show, { aggregate(query, prior) }, { client.searchAll(sources, query, page) }) { result ->
            store.recordMeasurements(result.measurements)
            val merged = if (prior == null) result else ThirdCms.mergeAggregates(prior, result)
            aggregateRows(query, merged)
        }
    }
    private fun aggregateRows(query: String, result: ThirdAggregateResult) {
        val back = { aggregateRows(query, result) }
        val summary = "第 ${result.page} 页 · 本页成功 ${result.successfulSources} 源 · ${result.failedSourceIds.size} 源部分失败"
        if (result.groups.isEmpty()) { info("聚合搜索无结果", "$summary\n未返回的片源可能超时、不可达或没有匹配内容。可改关键词重试。", ::show); return }
        val more = result.nextSourceIds.isNotEmpty() && result.groups.sumOf { it.hits.size } < 1000
        val labels = (if (more) listOf("加载下一页（${result.nextSourceIds.size} 个源）") else emptyList()) + result.groups.map { "${it.title} · ${it.hits.size} 个片源" }
        choose("$query · $summary", labels) { selected ->
            if (more && selected == 0) { aggregate(query, result); return@choose }
            val group = result.groups[selected - if (more) 1 else 0]
            choose("${group.title} · 选择片源", group.hits.map { "${it.source.name} · ${it.video.remarks}" }) { hit ->
                val row = group.hits[hit]; detail(row.source, row.video, back)
            }
        }
    }
    private fun browse(source: ThirdSource, page: Int = 1, category: String? = null, query: String? = null, fallback: (() -> Unit)? = null) {
        request("${source.name} · 正在加载", fallback, { browse(source, page, category, query, fallback) },
            { client.list(source, page, category, query) }) { result ->
            if (result.categories.isNotEmpty()) categories[source.id] = result.categories
            render(source, result, category, query)
        }
    }
    private fun render(source: ThirdSource, result: ThirdListPage, category: String?, query: String?) {
        val back = { render(source, result, category, query) }
        val labels = mutableListOf("搜索此源${query?.let { " · $it" }.orEmpty()}", "分类筛选", "切换片源")
        val previous = if (result.page > 1) labels.size.also { labels += "上一页" } else -1
        val next = if (result.hasMore) labels.size.also { labels += "下一页" } else -1
        val start = labels.size
        labels += result.videos.map { "${it.name}${it.remarks.takeIf(String::isNotBlank)?.let { text -> " · $text" }.orEmpty()}" }
        val title = "${source.name} · 第 ${result.page} 页${if (result.videos.isEmpty()) " · 暂无内容" else ""}"
        choose(title, labels) { index ->
            when (index) {
                0 -> search(source, back)
                1 -> {
                    val rows = categories[source.id].orEmpty()
                    choose("${source.name} · 分类", listOf("全部") + rows.map { it.name }) { selected -> browse(source, category = rows.getOrNull(selected - 1)?.id, fallback = back) }
                }
                2 -> show()
                previous -> if (previous >= 0) browse(source, result.page - 1, category, query, back)
                next -> if (next >= 0) browse(source, result.page + 1, category, query, back)
                else -> result.videos.getOrNull(index - start)?.let { detail(source, it, back) }
            }
        }
    }
    private fun search(source: ThirdSource, fallback: () -> Unit) {
        val entry = input("输入片名")
        val next = AlertDialog.Builder(activity).setTitle("${source.name} · 搜索").setView(entry).setNegativeButton("取消", null).setPositiveButton("搜索", null).create()
        open(next)
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val query = entry.text.toString().trim().take(100)
            if (query.isEmpty()) entry.error = "请输入片名" else browse(source, query = query, fallback = fallback)
        }
        entry.requestFocus()
    }
    private fun detail(source: ThirdSource, video: ThirdVideo, fallback: () -> Unit) {
        request("${video.name} · 正在加载详情", fallback, { detail(source, video, fallback) }, { client.detail(source, video.id) }) { detail -> detailMenu(source, detail, fallback) }
    }
    private fun detailMenu(source: ThirdSource, detail: ThirdDetail, fallback: () -> Unit) {
        val prior = store.history().firstOrNull { it.sourceId == source.id && it.video.id == detail.video.id }
        val labels = listOf(if (store.isFavorite(source, detail.video)) "取消片源内收藏" else "加入片源内收藏", "简介", "返回列表", "在其他片源搜索同名", "继续上次选集${prior?.let { " · ${it.episodeLabel}" } ?: " · 暂无记录"}") +
            detail.groups.map { (name, episodes) -> "$name · ${episodes.size} 集" }
        choose(detail.video.name, labels) { index ->
            when (index) {
                0 -> { store.toggleFavorite(source, detail.video); detailMenu(source, detail, fallback) }
                1 -> info(detail.video.name, detail.synopsis.ifBlank { "源站未提供简介。" }) { detailMenu(source, detail, fallback) }
                2 -> fallback()
                3 -> aggregate(detail.video.name)
                4 -> if (prior != null && detail.groups.getOrNull(prior.groupIndex)?.second?.getOrNull(prior.episodeIndex) != null)
                    play(source, detail, prior.groupIndex, prior.episodeIndex)
                    else info("继续上次选集", "尚无记录或源站分集已改变，请从下方线路重新选择。") { detailMenu(source, detail, fallback) }
                else -> detail.groups.getOrNull(index - 5)?.let { (line, episodes) ->
                    choose("${detail.video.name} · $line", episodes.map { it.label }) { selected ->
                        play(source, detail, index - 5, selected)
                    }
                }
            }
        }
    }
    private fun play(source: ThirdSource, detail: ThirdDetail, group: Int, episodeIndex: Int) {
        val episode = detail.groups.getOrNull(group)?.second?.getOrNull(episodeIndex) ?: return
        store.recordSelection(source, detail, group, episodeIndex)
        SourcePlaybackQueue.offer(source, detail, group, episodeIndex)
        close(); onPlay(episode.url, "${detail.video.name} · ${episode.label}")
    }
    private fun <T> request(title: String, fallback: (() -> Unit)?, retry: () -> Unit, task: () -> T, done: (T) -> Unit) {
        val loading = AlertDialog.Builder(activity).setTitle(title).setMessage("请稍候，最多约 20 秒。")
            .setNegativeButton("取消", null).create()
        open(loading)
        val token = ++generation
        workerStarted = true
        pending = worker.submit {
            val result = runCatching(task)
            main.post {
                if (destroyed || token != generation || dialog !== loading || !loading.isShowing) return@post
                result.onSuccess(done).onFailure { problem ->
                    val reason = if (problem.message?.matches(Regex("HTTP [0-9]{3}")) == true) problem.message!!
                        else if (problem is java.net.SocketTimeoutException) "请求超时" else "源站不可达或 JSON 格式不兼容"
                    val builder = AlertDialog.Builder(activity).setTitle("片源加载失败").setMessage("$reason。已有列表保留，可稍后重试。")
                        .setPositiveButton("重试") { _, _ -> retry() }.setNegativeButton("关闭", null)
                    if (fallback != null) builder.setNeutralButton("返回已有列表") { _, _ -> fallback() }
                    open(builder.create())
                }
            }
        }
    }
}
