// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.LibraryBackup
import com.hongguotv.core.Series
import com.hongguotv.core.RequestScope
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Isolated optional functions inspired by the public jycao2/hongguoTV fork. Function-specific
 * dialogs only: no settings page, updater, telemetry, optional source traffic or account writes.
 */
class FeatureTools(
    private val activity: Activity,
    private val library: Library,
    private val repository: ContentRepository,
    private val openSeries: (Series) -> Unit,
    private val onPlaySource: (String, String) -> Unit = { _, _ -> },
    private val onAppearanceChanged: (FeatureTheme) -> Unit = {},
    private val onDesktopChannel: () -> Unit = {},
    private val onChooseBackupFolder: (() -> Unit)? = null,
    private val onSeasonUpdates: (() -> Unit)? = null,
    private val onAppearance: (() -> Unit)? = null,
    private val onPhoneBackup: ((Boolean) -> Unit)? = null,
    private val onRestoreBackup: ((LocalFeatureBackup) -> Unit)? = null,
    private val portableExtras: (() -> Pair<String, String>)? = null,
    private val onCacheManage: (() -> Unit)? = null,
) {
    val preferences = FeaturePreferences(activity)
    private val main = Handler(Looper.getMainLooper())
    private val worker by lazy { Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-optional-tools") } }
    private var workerStarted = false
    private val transfer = TvTools(activity)
    private val sources = ThirdSourceScreen(activity, onPlaySource)
    private val backup = LocalBackupStorage(activity)
    private val skipState = FeatureSkipState()
    private var currentSeries: Series? = null
    private var currentSkipId = ""
    private var currentSkip = FeatureSkipMark()
    private var dialog: AlertDialog? = null
    private var generation = 0
    private var destroyed = false
    private var requestScope: RequestScope? = null
    private var pending: java.util.concurrent.Future<*>? = null
    private var backupInFlight = false
    private var nextAutoBackupAttempt = 0L
    val showing: Boolean get() = dialog?.isShowing == true || transfer.showing || sources.showing
    val seekStepMs: Long get() = preferences.seekStepSeconds * 1000L
    val holdSpeed: Float get() = preferences.holdSpeed
    val theme: FeatureTheme get() = preferences.theme
    fun keyAction(keyCode: Int) = preferences.keyAction(keyCode)
    fun favoriteGroups() = preferences.groups()
    /** null = all, empty string = ungrouped; the Library and cloud records are never modified. */
    fun filterFavorites(items: List<Series>, group: String?): List<Series> {
        if (group == null) return items
        val assignments = preferences.groupAssignments()
        return items.filter { if (group.isEmpty()) it.id !in assignments else assignments[it.id] == group }
    }
    fun showFavoriteGroupPicker(select: (String?) -> Unit) {
        val groups = preferences.groups()
        choose("收藏分组筛选", listOf("全部收藏", "未分组") + groups) { index -> select(when (index) { 0 -> null; 1 -> ""; else -> groups[index - 2] }) }
    }
    fun setCurrentSeries(series: Series?) { currentSeries = series }
    fun playbackStarted(seriesId: String) {
        skipState.started(seriesId); currentSkipId = seriesId; currentSkip = preferences.skip(seriesId)
        preferences.playbackStarted(seriesId)
        if (currentSeries?.id != seriesId) currentSeries = library.history().firstOrNull { it.series.id == seriesId }?.series
            ?: library.favorites().firstOrNull { it.id == seriesId }
        maybeAutoBackup()
    }
    fun onPlaybackTick(seriesId: String, positionMs: Long, durationMs: Long): Long? {
        if (currentSkipId != seriesId) { currentSkipId = seriesId; currentSkip = preferences.skip(seriesId) }
        return skipState.target(seriesId, positionMs, durationMs, currentSkip)
    }
    fun recordPlayback(deltaMs: Long) { preferences.recordPlayback(deltaMs); maybeAutoBackup() }
    fun close() { generation++; dialog?.dismiss(); dialog = null; requestScope?.cancel(); pending?.cancel(true); transfer.close(); sources.close() }
    fun destroy() {
        if (destroyed) return
        close(); destroyed = true; preferences.flushStats(); transfer.destroy(); sources.destroy()
        if (workerStarted) worker.shutdown()
    }
    private fun open(next: AlertDialog) {
        if (destroyed || activity.isFinishing || activity.isDestroyed) return
        dialog?.dismiss(); dialog = next
        next.setOnDismissListener { if (dialog === next) { dialog = null; generation++; requestScope?.cancel(); pending?.cancel(true) } }
        next.show(); next.listView?.requestFocus()
    }
    private fun choose(title: String, labels: List<String>, action: (Int) -> Unit): Unit = open(
        AlertDialog.Builder(activity).setTitle(title).setItems(labels.toTypedArray()) { _, index -> action(index) }
            .setNegativeButton("关闭", null).create())
    private fun info(title: String, message: String, next: (() -> Unit)? = null): Unit = open(
        AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("知道了") { _, _ -> next?.invoke() }.create())
    private fun confirm(title: String, message: String, action: () -> Unit) {
        val next = AlertDialog.Builder(activity).setTitle(title).setMessage(message).setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ -> action() }.create()
        open(next); next.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }
    private fun toast(text: String) { if (!destroyed) Toast.makeText(activity, text, Toast.LENGTH_SHORT).show() }
    fun show() {
        val series = currentSeries
        choose("实用工具", listOf(
            if (series == null) "按剧片头片尾 / 收藏分组" else "当前剧 · ${series.title}",
            "收藏分组", "备份与恢复", "遥控器快捷键", "本机观看统计", "内容接口诊断", "显示外观", "随机推荐", "自定义片源（${sources.store.sources().size}）", "电视桌面续播", "清理缓存",
        ) + if (onSeasonUpdates != null) listOf("追剧 · 下一季") else emptyList()) { index -> when (index) {
            0 -> if (series != null) showSeries(series) else chooseSeries()
            1 -> groupsMenu()
            2 -> backupsMenu()
            3 -> remoteMenu()
            4 -> stats()
            5 -> diagnostics()
            6 -> appearance()
            7 -> randomMenu()
            8 -> { close(); sources.show() }
            9 -> { close(); onDesktopChannel() }
            10 -> { close(); onCacheManage?.invoke() }
            11 -> { close(); onSeasonUpdates?.invoke() }
        } }
    }
    private fun chooseSeries() {
        val rows = (library.history().map { it.series } + library.favorites()).distinctBy { it.id }.take(200)
        if (rows.isEmpty()) { info("按剧工具", "先打开一部剧，或添加收藏后再使用片头片尾和分组。") ; return }
        choose("选择剧集", rows.map { it.title }) { showSeries(rows[it]) }
    }
    fun showSeries(series: Series) {
        currentSeries = series
        val mark = preferences.skip(series.id)
        choose(series.title, listOf(
            "片头跳过 · ${mark.introSeconds} 秒", "片尾跳过 · ${mark.outroSeconds} 秒",
            "收藏分组 · ${preferences.groupOf(series.id) ?: "未分组"}", "打开详情", "跨季连播 · ${if (preferences.nextSeason) "开" else "关"}",
            "自动跳过不兼容编码 · ${if (preferences.autoSkipIncompatible) "开" else "关"}",
        )) { index -> when (index) {
            0 -> skipDuration(series, true)
            1 -> skipDuration(series, false)
            2 -> assignGroup(series)
            3 -> { close(); openSeries(series) }
            4 -> { preferences.nextSeason = !preferences.nextSeason; showSeries(series) }
            5 -> { preferences.autoSkipIncompatible = !preferences.autoSkipIncompatible; showSeries(series) }
        } }
    }
    private fun skipDuration(series: Series, intro: Boolean) {
        val values = listOf(0, 5, 10, 15, 30, 60, 90, 120)
        choose(if (intro) "${series.title} · 跳过片头" else "${series.title} · 跳过片尾", values.map { if (it == 0) "关闭" else "$it 秒" } + "自定义（0—600 秒）") { index ->
            fun save(seconds: Int) {
                val old = preferences.skip(series.id)
                preferences.setSkip(series.id, if (intro) seconds else old.introSeconds, if (intro) old.outroSeconds else seconds)
                if (currentSkipId == series.id) currentSkip = preferences.skip(series.id)
                showSeries(series)
            }
            if (index < values.size) save(values[index]) else numeric("跳过秒数（0—600）", 0, 600, ::save)
        }
    }
    private fun numeric(title: String, minimum: Int, maximum: Int, save: (Int) -> Unit) {
        val entry = EditText(activity).apply { inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true); hint = "$minimum—$maximum" }
        val next = AlertDialog.Builder(activity).setTitle(title).setView(entry).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        open(next); entry.requestFocus()
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = entry.text.toString().trim().toIntOrNull()
            if (value == null || value !in minimum..maximum) entry.error = "请输入 $minimum—$maximum 之间的整数"
            else save(value)
        }
    }
    private fun groupName(save: (String) -> Unit) {
        val entry = EditText(activity).apply { setSingleLine(true); hint = "分组名称，最多 32 字" }
        val next = AlertDialog.Builder(activity).setTitle("新建收藏分组").setView(entry).setNegativeButton("取消", null).setPositiveButton("创建", null).create()
        open(next); entry.requestFocus()
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val clean = FeaturePolicy.groupName(entry.text.toString())
            if (!preferences.addGroup(clean)) entry.error = "名称为空、已存在或超过 20 组上限" else save(clean)
        }
    }
    private fun assignGroup(series: Series) {
        if (!library.favorite(series.id)) {
            confirm("加入本机收藏", "先将《${series.title}》加入本机收藏，再选择分组。") { library.toggle(series); assignGroup(series) }
            return
        }
        val groups = preferences.groups()
        choose("${series.title} · 收藏分组", listOf("未分组", "新建分组") + groups) { index -> when (index) {
            0 -> { preferences.assignGroup(series.id, null); showSeries(series) }
            1 -> groupName { name -> preferences.assignGroup(series.id, name); showSeries(series) }
            else -> { preferences.assignGroup(series.id, groups[index - 2]); showSeries(series) }
        } }
    }
    private fun groupsMenu() {
        val groups = preferences.groups()
        choose("收藏分组", listOf("全部收藏", "未分组", "新建分组", "删除分组") + groups.map { "$it（${filterFavorites(library.favorites(), it).size}）" }) { index -> when (index) {
            0 -> groupRows(null, "全部收藏")
            1 -> groupRows("", "未分组")
            2 -> groupName { groupsMenu() }
            3 -> if (groups.isEmpty()) info("删除分组", "暂无分组。") else choose("删除哪个分组", groups) { selected ->
                confirm("删除 ${groups[selected]}？", "仅删除分组，剧集继续保留在本机收藏中。") { preferences.removeGroup(groups[selected]); groupsMenu() }
            }
            else -> groupRows(groups[index - 4], groups[index - 4])
        } }
    }
    private fun groupRows(group: String?, label: String) {
        val rows = filterFavorites(library.favorites(), group)
        if (rows.isEmpty()) { info(label, "该分组暂无收藏。可在按剧工具中分组。") ; return }
        choose(label, rows.map { it.title }) { index -> showSeries(rows[index]) }
    }
    private fun backupsMenu(): Unit = choose("备份与恢复", listOf(
        "立即保存本机双副本", "恢复本机备份", "每日自动本机备份 · ${if (preferences.autoBackup) "开" else "关"}", "手机二维码备份与恢复", "备份保存范围", "附加片源、外观与弹幕 · ${if (preferences.backupIncludesExtras) "开" else "关"}",
    ) + if (onChooseBackupFolder != null) listOf("选择外部备份目录（系统）") else emptyList()) { index -> when (index) {
        0 -> saveBackup(true)
        1 -> restoreBackup()
        2 -> { preferences.autoBackup = !preferences.autoBackup; if (preferences.autoBackup) maybeAutoBackup(); backupsMenu() }
        3 -> phoneBackup()
        4 -> info("备份范围", "本机双副本含红果收藏、历史、稍后看、播放设置，以及按剧片头片尾和收藏分组。每日自动备份默认关闭，只在使用应用时最多每天保存一次。\n\n本机副本在应用私有目录，清除数据或卸载会丢失。通过系统选择外部备份目录后，保存时也会复制一份；USB 断开或目录失效时仍保留本机副本。\n\n手机二维码与本机副本使用相同备份格式，包含红果记录与按剧工具。开启附加备份后还包含片源、第三方收藏/历史/进度、主题、背景与弹幕偏好。片源地址可能含私人令牌，请妥善保存文件。恢复全局播放/显示偏好需在电视上另行勾选。账号登录与设备标识不导出。\n\n${backup.externalStatus()}")
        5 -> {
            if (!preferences.backupIncludesExtras) confirm("将片源与外观加入备份？", "片源地址可能含私人令牌；背景图片、弹幕偏好、第三方收藏与历史也会进入 JSON 文件。请妥善保存备份。平台登录与设备标识不会导出。") { preferences.backupIncludesExtras = true; backupsMenu() }
            else { preferences.backupIncludesExtras = false; backupsMenu() }
        }
        6 -> { close(); onChooseBackupFolder?.invoke() }
    } }
    private fun maybeAutoBackup() {
        val now = System.currentTimeMillis()
        if (preferences.autoBackup && !backupInFlight && now >= nextAutoBackupAttempt &&
            (preferences.lastBackupAt == 0L || now - preferences.lastBackupAt >= 24 * 60 * 60 * 1000L)) saveBackup(false)
    }
    private fun saveBackup(manual: Boolean) {
        if (backupInFlight || !library.isLoaded || destroyed) { if (manual) toast("已有备份任务正在进行") ; return }
        val snapshot = library.snapshot(); val tools = preferences.export(); val now = System.currentTimeMillis()
        val includeExtras = preferences.backupIncludesExtras
        backupInFlight = true; workerStarted = true
        worker.execute {
            val result = runCatching {
                val extras = if (includeExtras) portableExtras?.invoke() ?: throw IllegalStateException("附加备份不可用") else null
                backup.save(LibraryBackup.encode(snapshot), tools, now, extras?.first, extras?.second)
            }
            main.post {
                backupInFlight = false
                result.onSuccess { preferences.lastBackupAt = now }
                    .onFailure { nextAutoBackupAttempt = now + 60 * 60 * 1000L }
                if (manual && !destroyed) info("本机双副本", (if (result.isSuccess) "已保存当前记录。之前的有效副本也已保留，可从备份与恢复入口合并恢复。" else "本次备份失败，之前的有效副本保留。可稍后重试或使用手机二维码导出。") + "\n\n${backup.externalStatus()}")
            }
        }
        if (manual) toast("正在保存本机备份…")
    }
    private fun restoreBackup(): Unit = task("正在读取本机备份", { backup.load() }) { stored ->
        if (stored == null) { info("恢复本机备份", "尚无有效的本机备份。可从手机二维码导入之前导出的红果记录。") ; return@task }
        if (onRestoreBackup != null) { close(); onRestoreBackup.invoke(stored); return@task }
        val stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(stored.savedAt))
        confirm("合并恢复本机备份", "$stamp\n${stored.data.favorites.size} 部收藏，${stored.data.history.size} 条观看记录。\n\n收藏合并，观看进度保留较新的记录；现有播放设置保留。片头片尾和分组仅补充本机缺少的条目。") {
            runCatching { preferences.restore(stored.tools); library.restore(stored.data, false) }
                .onSuccess { currentSkip = preferences.skip(currentSkipId); info("恢复完成", "已合并恢复本机记录。返回书架可查看。") }
                .onFailure { info("无法恢复", "备份内容校验未通过，原有红果记录保留。") }
        }
    }
    private fun phoneBackup() {
        if (onPhoneBackup != null) { val extras = preferences.backupIncludesExtras; close(); onPhoneBackup.invoke(extras); return }
        val snapshot = library.snapshot()
        task("正在准备手机备份", { LibraryBackup.encode(snapshot) }) { value ->
            close(); transfer.libraryTransfer(value, null) { incoming, settings -> library.restore(incoming, settings); toast("已合并恢复红果记录") }
        }
    }
    private fun remoteMenu(): Unit = choose("遥控器快捷键", listOf("左右短按快进 · ${preferences.seekStepSeconds} 秒", "长按右键倍速 · ${preferences.holdSpeed}×", "菜单键与彩色键映射")) { index -> when (index) {
        0 -> choose("左右短按快进", FeaturePolicy.seekSteps.map { "$it 秒" }) { preferences.seekStepSeconds = FeaturePolicy.seekSteps[it]; remoteMenu() }
        1 -> choose("长按右键倍速", FeaturePolicy.holdSpeeds.map { "$it×" }) { preferences.holdSpeed = FeaturePolicy.holdSpeeds[it]; remoteMenu() }
        2 -> {
            val labels = listOf("菜单键", "红键", "绿键", "黄键", "蓝键")
            choose("按键映射 · 仅播放时生效", FeaturePreferences.REMOTE_KEYS.mapIndexed { i, code -> "${labels[i]} · ${preferences.keyAction(code).label}" }) { key ->
                choose(labels[key], FeatureKeyAction.entries.map { it.label }) { action -> preferences.setKeyAction(FeaturePreferences.REMOTE_KEYS[key], FeatureKeyAction.entries[action]); remoteMenu() }
            }
        }
    } }
    private fun appearance() {
        if (onAppearance != null) { close(); onAppearance.invoke(); return }
        choose("显示外观", FeatureTheme.entries.map { (if (it == preferences.theme) "✓ " else "") + it.label }) { index ->
        val theme = FeatureTheme.entries[index]; preferences.theme = theme; onAppearanceChanged(theme); toast("已选择${theme.label}外观")
        }
    }
    fun showCache(artwork: ArtworkLoader) {
        val cache = SafeCacheManager(activity.cacheDir) {
            activity.getSharedPreferences("native-feed-startup-v1", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
        task("正在检查封面缓存", { cache.bytes() }) { bytes ->
            confirm("清理封面缓存", "封面磁盘缓存约 ${bytes / 1024} KB。清理后封面会重新加载，收藏、观看记录、片源和背景图片继续保留。") {
                artwork.cancelPage(clearMemory = true)
                task("正在清理封面缓存", { cache.clear() }) { result ->
                    info("缓存清理", "已清理 ${result.removedBytes / 1024} KB" + if (result.failures == 0) "。" else "，${result.failures} 个文件暂时无法清理，可稍后重试。")
                }
            }
        }
    }
    private fun stats() {
        val stats = preferences.stats()
        fun time(ms: Long) = "${ms / 3_600_000} 小时 ${ms / 60_000 % 60} 分"
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(Date())
        val message = "累计播放 ${time(stats.playbackMs)}\n今天播放 ${time(stats.days[today] ?: 0)}\n播放开始 ${stats.starts} 次（按每集计）\n看过 ${stats.seriesCount} 部（最多记 1000 部）\n\n本机收藏 ${library.favorites().size} 部；观看记录 ${library.history().size} 条。\n从本版本开始累计，仅本机保存，不向服务器上报。暂停、后台和睡眠时间不计入播放时长。"
        val next = AlertDialog.Builder(activity).setTitle("本机观看统计").setMessage(message).setNegativeButton("关闭", null)
            .setNeutralButton("清空统计") { _, _ -> confirm("清空统计？", "仅清空本机统计，收藏和观看记录保留。") { preferences.resetStats(); this.stats() } }.create()
        open(next)
    }
    private fun diagnostics() {
        val type = library.contentType
        task("正在诊断当前分类内容接口", {
            val start = System.nanoTime(); val page = repository.home(1, type)
            "当前分类首屏返回 ${page.items.size} 部内容。\n耗时 ${(System.nanoTime() - start) / 1_000_000} 毫秒。\n\n仅检查内容读取，不能证明账号登录、所有播放 CDN 或电视解码能力均正常。不会输出签名、Cookie 或设备标识。"
        }) { info("接口诊断", it) }
    }
    private fun randomMenu(): Unit = choose("随机推荐", listOf("从本机收藏挑选", "从稍后看挑选", "从当前分类发现")) { index -> when (index) {
        0 -> randomRows(library.favorites())
        1 -> randomRows(library.later())
        else -> {
            val cached = library.cachedHome(library.contentType)?.items.orEmpty()
            if (cached.isNotEmpty()) randomRows(cached) else {
                val type = library.contentType
                task("正在读取推荐", { repository.home(1, type).items }) { randomRows(it) }
            }
        }
    } }
    private fun randomRows(rows: List<Series>) {
        val hidden = library.hidden()
        val candidates = rows.filterNot { it.id in hidden }.distinctBy { it.id }.shuffled().take(8)
        if (candidates.isEmpty()) info("随机推荐", "暂无可推荐的内容。先添加收藏、稍后看或浏览发现页。")
        else choose("为你随机挑选", candidates.map { it.title }) { index -> close(); openSeries(candidates[index]) }
    }
    private fun <T> task(title: String, action: () -> T, done: (T) -> Unit) {
        val next = AlertDialog.Builder(activity).setTitle(title).setMessage("请稍候…").setNegativeButton("取消", null).create()
        open(next); val token = ++generation; workerStarted = true
        val scope = RequestScope(); requestScope = scope
        pending = worker.submit {
            val result = runCatching { scope.run(action) }
            main.post {
                if (destroyed || generation != token || dialog !== next || !next.isShowing) return@post
                result.onSuccess(done).onFailure { info("操作未完成", "网络、存储或响应校验失败。已有记录保留，可稍后重试。") }
            }
        }
    }
}
