// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 账户界面与状态协调：连接登录、云端记录及页面刷新回调。

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import com.hongguotv.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class FavoriteState(
    val rows: List<CloudFavorite> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val message: String = "尚未读取账户收藏",
    val coverage: CloudCoverage? = null,
)

internal data class ReservationState(
    val rows: List<CloudReservation> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val message: String = "尚未读取预约记录",
    val coverage: CloudCoverage? = null,
)

/**
 * Cloud history is kept separate from guest records; only explicit playback creates local history.
 */
class AccountController internal constructor(private val activity: Activity, private val play: (CloudProgress) -> Unit,
    private val store: AccountStore, private val http: okhttp3.OkHttpClient) {
    constructor(activity: Activity, play: (CloudProgress)->Unit):this(activity,play,AccountStore(activity),
        okhttp3.OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
            .callTimeout(30,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build())
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val client = CloudAccountClient(http)
    private var vault = AccountVault()
    private var vaultLoaded = false
    private var vaultReadError = false
    private var selectedAccountId: String? = null
    private var sessionVerified = false
    val accountProfile: CloudProfile? get() = profile
    val savedAccountCount: Int get() = vault.count
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var credentials: AccountCredentials? = null
    private var profile: CloudProfile? = null
    private var history = emptyList<CloudProgress>()
    private var historyLoaded = false
    private val acceptedReservations = mutableSetOf<String>()
    private var favorites = FavoriteState()
    private val unconfirmedFavorites = mutableMapOf<String, Boolean>()
    private var reservations = emptyMap<Boolean, ReservationState>()
    private var status = "未登录"
    private var busy = false
    private var lastUpload = 0L
    private var lastEpisode = ""
    private var pending: Pair<CloudProgress, Int>? = null
    private var lastProgress: Pair<CloudProgress, Int>? = null
    private var draining = false
    private var dialog: AlertDialog? = null
    var onHistoryChanged: (() -> Unit)? = null

    internal fun recommendationCredentials() = credentials?.takeIf { profile != null && sessionVerified }

    internal val recommendationIdentity
        get() = "$generation:${if(sessionVerified) profile?.id.orEmpty() else ""}"

    val signedIn
        get() = credentials != null

    val accountLabel
        get() = profile?.nickname ?: if (signedIn) "红果账号" else "未登录"

    val historyStatus
        get() = status

    val historyLoading
        get() = busy

    val historyReady
        get() = signedIn && historyLoaded

    val historyScope
        get() = generation

    fun historyRows(): List<CloudProgress> = if (signedIn && sessionVerified) history.toList() else emptyList()

    fun refreshHistory() {
        refresh(false)
    }

    internal fun reservationState(online: Boolean): ReservationState =
        if (signedIn) reservations[online] ?: ReservationState()
        else ReservationState(message = "未登录")

    fun reservationLabel(work: RelatedWork): String =
        when {
            !signedIn -> "登录后预约"
            reservationState(false).rows.any {
                it.series.id == work.series.id || it.series.id == "reservation:${work.series.id}"
            } -> "已预约"
            work.series.id in acceptedReservations -> "刷新预约状态"
            else -> "预约"
        }

    fun reserve(work: RelatedWork, done: (String) -> Unit) {
        if (!sessionVerified) { done("账号尚未验证，请先刷新或重新登录"); return }
        val account =
            credentials
                ?: run {
                    show()
                    return
                }
        if (busy) {
            toast("账号操作进行中，请稍候")
            return
        }
        if (reservationLabel(work) == "已预约") {
            done("该作品已在当前账号的预约列表中")
            return
        }
        if (work.series.id in acceptedReservations) {
            refreshReservations()
            done("正在刷新预约列表，请稍后核对；未显示前暂不能确认预约生效。")
            return
        }
        task(
            { client.reserve(account, work) },
            {
                acceptedReservations += work.series.id
                changed()
                done("红果已接受预约请求，尚未在账号列表确认。请刷新预约状态，或在手机红果核对是否新增。")
            },
            { done("预约未完成：${safeError(it)}") },
        )
    }

    internal fun favoriteState(): FavoriteState =
        if (signedIn) favorites else FavoriteState(message = "未登录")

    fun accountFavorite(id: String) = signedIn && favorites.rows.any { it.series.id == id }

    fun favoriteNeedsRefresh(id: String) = id in unconfirmedFavorites

    fun favoriteActionLabel(id: String): String =
        when {
            !signedIn -> "登录后加入账户收藏"
            favorites.loading -> "账户收藏处理中…"
            favoriteNeedsRefresh(id) -> "刷新账户收藏状态"
            accountFavorite(id) -> "取消账户收藏"
            else -> "加入账户收藏"
        }

    fun changeFavorite(series: Series, add: Boolean, done: (String) -> Unit) {
        if (!sessionVerified) { done("账号尚未验证，请先刷新或重新登录"); return }
        val account =
            credentials
                ?: run {
                    show()
                    return
                }
        if (closed || favorites.loading) {
            done("账户收藏正在处理，请稍候")
            return
        }
        if (favoriteNeedsRefresh(series.id)) {
            refreshFavorites()
            done("上次操作尚未确认，已刷新账户收藏，请核对后再操作。")
            return
        }
        val token = generation
        favorites = favorites.copy(loading = true, message = "正在核对并更新账户收藏…")
        changed()
        worker.execute {
            var attempted = false
            val result = runCatching {
                check(!closed && token == generation)
                val before = client.favoritesPage(account)
                if (before.items.any { it.series.id == series.id } == add) before to false
                else {
                    check(!closed && token == generation)
                    attempted = true
                    client.setFavorite(account, series.id, add)
                    check(!closed && token == generation)
                    client.favoritesPage(account) to true
                }
            }
            main.post {
                if (closed || token != generation) return@post
                val message =
                    result.fold(
                        { (page, wrote) ->
                            val rows = page.items
                            val confirmed = rows.any { it.series.id == series.id } == add &&
                                (add || page.coverage.complete)
                            if (confirmed) unconfirmedFavorites.remove(series.id)
                            else if (wrote) unconfirmedFavorites[series.id] = add
                            favorites =
                                FavoriteState(
                                    rows,
                                    loaded = true,
                                    message =
                                        if (confirmed) "已核对当前收藏，共读取 ${rows.size} 部；${page.coverage.message}"
                                        else if (wrote) "请求已提交，列表尚未确认；${page.coverage.message}"
                                        else "本次列表未显示该剧，未提交操作；${page.coverage.message}",
                                    coverage = page.coverage,
                                )
                            if (!confirmed && wrote) "请求已提交，但账户列表尚未确认生效。请刷新或在手机红果核对，不要连续重复提交。"
                            else if (!confirmed) "本次读取未显示该剧；账户列表可能不全，未提交取消操作，请在手机红果核对。"
                            else if (wrote) if (add) "已加入账户收藏，并已在账户列表确认" else "已取消账户收藏，并已从本次读取的列表移除"
                            else if (add) "这部剧已在账户收藏中，无需重复添加" else "本次读取的账户列表中已没有这部剧，无需重复取消"
                        },
                        { error ->
                            if (attempted) unconfirmedFavorites[series.id] = add
                            favorites =
                                favorites.copy(
                                    loading = false,
                                    message =
                                        if (attempted) "收藏操作未确认，请刷新或在手机核对"
                                        else "读取失败：${safeError(error)}",
                                )
                            if (attempted) "收藏操作未确认：${safeError(error)}。请先刷新或在手机核对。"
                            else "未修改账户收藏：${safeError(error)}"
                        },
                    )
                changed()
                done(message)
            }
        }
    }

    fun refreshFavorites() {
        val account = credentials?.takeIf {sessionVerified} ?: return
        if (closed || favorites.loading) return
        val token = generation
        favorites = favorites.copy(loading = true, message = "正在读取账户收藏…")
        changed()
        worker.execute {
            if (closed || token != generation) return@execute
            val result = runCatching { client.favoritesPage(account) }
            main.post {
                if (closed || token != generation) return@post
                favorites =
                    result.fold(
                        { page ->
                            unconfirmedFavorites.entries.removeAll { (id, target) ->
                                page.items.any { it.series.id == id } == target && (target || page.coverage.complete)
                            }
                            FavoriteState(page.items, loaded = true,
                                message = "已读取 ${page.items.size} 部；${page.coverage.message}", coverage = page.coverage)
                        },
                        { favorites.copy(loading = false, message = "读取失败：${safeError(it)}") },
                    )
                changed()
            }
        }
    }

    fun refreshReservations() {
        val account = credentials?.takeIf {sessionVerified} ?: return
        if (closed || reservations.values.any { it.loading }) return
        val token = generation
        reservations =
            listOf(true, false).associateWith {
                reservationState(it).copy(loading = true, message = "正在读取预约记录…")
            }
        changed()
        // Independent results: failure in one category must not masquerade as an empty list.
        worker.execute {
            for (online in listOf(true, false)) {
                if (closed || token != generation) return@execute
                val result = runCatching { client.reservationsPage(account, online) }
                main.post {
                    if (closed || token != generation) return@post
                    val before = reservationState(online)
                    reservations =
                        reservations +
                            (online to
                                result.fold(
                                    { page ->
                                        ReservationState(
                                            page.items,
                                            loaded = true,
                                            message = "已读取 ${page.items.size} 部；${page.coverage.message}",
                                            coverage = page.coverage,
                                        )
                                    },
                                    {
                                        before.copy(
                                            loading = false,
                                            message = "读取失败：${safeError(it)}",
                                        )
                                    },
                                ))
                    changed()
                }
            }
        }
    }

    private fun changed() {
        onHistoryChanged?.invoke()
    }

    val showing
        get() = dialog?.isShowing == true

    init { readSavedVault() }
    private fun readSavedVault() {
        val token = generation
        worker.execute {
            val saved = runCatching { store.load() }
            main.post {
                if (closed || token != generation) return@post
                vaultLoaded = true
                vaultReadError = saved.isFailure
                saved.onSuccess { next ->
                    vault = next
                    credentials = next.active?.credentials ?: next.legacy?.takeIf {next.legacyActive}
                    profile = next.active?.profile
                    selectedAccountId = next.activeId
                }
                status = if (credentials != null) "登录态已恢复，正在验证当前账号"
                    else if (saved.isFailure) "登录态无法读取，请重试；本机记录不受影响"
                    else if(vault.count>0) "已保存${vault.count}个账号，点击头像切换" else "未登录"
                changed()
                if (credentials != null) refresh(false)
            }
        }
    }

    /** Invalidate before persistence/network so stale reads and queued progress cannot cross users. */
    private fun clearSession(nextStatus:String) {
        generation++; busy=false; sessionVerified=false
        credentials=null;profile=null;selectedAccountId=null
        history=emptyList();historyLoaded=false;favorites=FavoriteState();reservations=emptyMap()
        unconfirmedFavorites.clear();acceptedReservations.clear()
        pending=null;lastProgress=null;draining=false;lastUpload=0;lastEpisode=""
        http.dispatcher.cancelAll();status=nextStatus
    }
    private fun selectSaved(action:()->AccountVault) {
        if(closed)return
        clearSession("正在切换账号…");val token=generation;changed()
        worker.execute {
            // Serialized, explicit selection/logout intents remain durable after Activity closure.
            // Only their UI/network activation below is cancelled by a later scope.
            val result=runCatching(action)
            main.post {
                if(closed || token!=generation)return@post
                result.onSuccess { next->
                    vault=next;credentials=next.active?.credentials ?: next.legacy?.takeIf{next.legacyActive}
                    profile=next.active?.profile;selectedAccountId=next.activeId
                    status=if(credentials!=null)"已切换，正在验证账号和刷新云端记录" else "已暂停云端同步，本机记录继续保留"
                    changed();if(credentials!=null)refresh(false)
                }.onFailure {status="切换未保存，云端同步已停止，请重试";changed();toast(status)}
            }
        }
    }
    fun switchAccount(id:String) {
        if(selectedAccountId==id && sessionVerified){toast("当前已是这个账号");return}
        selectSaved {store.select(id)}
    }
    fun useLocalOnly() = selectSaved {store.deactivate()}
    fun showSwitcher() {
        if(closed)return
        if(!vaultLoaded){toast("正在读取已保存账号，请稍候");return}
        if(vaultReadError){showStoreRecovery();return}
        val rows=vault.accounts.toList();val legacy=vault.legacy!=null
        val labels=rows.map {row->(if(selectedAccountId==row.profile.id)"✓ " else "")+row.profile.nickname+" · …"+row.profile.id.takeLast(4)}+
            (if(legacy) listOf("旧版账号（待验证）") else emptyList())+listOf("＋ 添加 / 重新登录账号","仅使用本机记录（暂停云端同步）","管理已保存账号")
        showDialog(AlertDialog.Builder(activity).setTitle("切换账号 · ${vault.count}/10").setItems(labels.toTypedArray()){_,index->
            when {
                index<rows.size->switchAccount(rows[index].profile.id)
                legacy && index==rows.size->selectSaved {store.selectLegacy()}
                index==rows.size+(if(legacy)1 else 0)->showLoginMethods()
                index==rows.size+(if(legacy)1 else 0)+1->useLocalOnly()
                else->manageAccounts()
            }
        }.setNegativeButton("关闭",null))
    }
    private fun showStoreRecovery() {
        showDialog(AlertDialog.Builder(activity).setTitle("保存的登录态无法读取")
            .setMessage("可能是登录数据损坏或系统密钥已失效。可以重试读取，或仅重置电视保存的全部登录态，再重新登录。本机收藏、观看记录、片源和设备身份继续保留。")
            .setPositiveButton("重试读取"){_,_->clearSession("正在重新读取登录态");changed();readSavedVault()}
            .setNeutralButton("重置登录态"){_,_->
                showDialog(AlertDialog.Builder(activity).setTitle("清除无法读取的登录态？")
                    .setMessage("这会移除电视保存的所有红果登录状态，需要重新登录；不会清除本机收藏、观看记录或平台账号。")
                    .setNegativeButton("取消",null).setPositiveButton("确认重置"){_,_->
                        clearSession("正在重置登录态");val token=generation;changed()
                        worker.execute {val result=runCatching{store.resetUnreadable()};main.post {
                            if(closed || token!=generation)return@post
                            result.onSuccess{vault=it;vaultReadError=false;status="登录态已重置，本机记录保留";changed();showLoginMethods()}
                                .onFailure{status="重置未完成，请重试读取";changed();toast(status)}
                        }}
                    })
            }.setNegativeButton("关闭",null))
    }
    private fun showLoginMethods() {
        if(vaultReadError){showStoreRecovery();return}
        // Cancel outstanding reads but retain the current session if adding a login is cancelled.
        generation++;http.dispatcher.cancelAll();busy=false;pending=null;lastProgress=null;draining=false;lastUpload=0;lastEpisode=""
        favorites=favorites.copy(loading=false);reservations=reservations.mapValues{it.value.copy(loading=false)}
        changed()
        showDialog(AlertDialog.Builder(activity).setTitle("添加 / 重新登录红果账号")
            .setItems(arrayOf("使用红果 App 扫码登录","手机号 + 验证码登录")){_,index->if(index==0)qrLogin() else phone()}
            .setNegativeButton("取消",null))
    }
    private fun manageAccounts() {
        val rows=vault.accounts.toList();val legacy=vault.legacy!=null
        if(rows.isEmpty() && !legacy){toast("尚无已保存账号");return}
        val labels=rows.map{it.profile.nickname+" · …"+it.profile.id.takeLast(4)}+if(legacy)listOf("旧版账号（待验证）")else emptyList()
        showDialog(AlertDialog.Builder(activity).setTitle("移除电视保存的登录态").setItems(labels.toTypedArray()){_,i->
            val id=rows.getOrNull(i)?.profile?.id
            showDialog(AlertDialog.Builder(activity).setTitle("移除此登录态？")
                .setMessage("仅移除电视保存的登录状态，不删除平台账号、本机收藏或观看记录。其他账号继续保留。")
                .setPositiveButton("移除"){_,_->if(id==selectedAccountId || (id==null && vault.legacyActive))logout()
                    else {val token=generation;worker.execute{val result=runCatching{if(id==null)store.forgetLegacy()else store.remove(id)};main.post{if(!closed && token==generation)result.onSuccess{vault=it;changed();showSwitcher()}.onFailure{toast("移除失败，请重试")}}}}}
                .setNegativeButton("取消",null))
        }.setNegativeButton("关闭",null))
    }

    private fun toast(value: String) {
        if (!closed) Toast.makeText(activity, value, Toast.LENGTH_LONG).show()
    }

    private fun safeError(error: Throwable) =
        when (error) {
            is java.net.SocketTimeoutException -> "账号接口连接超时，请稍后重试"
            is java.net.UnknownHostException -> "无法连接账号接口，请检查网络"
            else ->
                error.message.orEmpty().replace(Regex("https?://\\S+"), "[地址]").take(150).ifBlank {
                    "账号操作失败"
                }
        }

    fun progress(id: String): CloudProgress? =
        if (credentials != null && sessionVerified) history.firstOrNull { it.series.id == id } else null

    private fun showDialog(builder: AlertDialog.Builder) {
        dialog?.dismiss()
        dialog = builder.create()
        dialog?.show()
    }

    fun show() {
        if (closed) return
        if(vaultReadError){showStoreRecovery();return}
        val signed = credentials != null
        val choices =
            if (signed) arrayOf("云端继续观看（最近 50 部）", "刷新账号和云端记录", "退出当前红果账号", "切换已保存账号", "添加 / 重新登录账号", "管理已保存账号")
            else arrayOf("使用红果 App 扫码登录", "手机号 + 验证码登录", "切换已保存账号")
        showDialog(
            AlertDialog.Builder(activity)
                .setTitle(profile?.let { "红果账号 · ${it.nickname}" } ?: "登录红果账号")
                .setItems(choices) { _, index ->
                    if (!signed) {
                        when(index){0->qrLogin();1->phone();else->showSwitcher()}
                    } else
                        when (index) {
                            0 -> showHistory()
                            1 -> refresh(true)
                            2 -> logout()
                            3 -> showSwitcher()
                            4 -> showLoginMethods()
                            5 -> manageAccounts()
                        }
                }
                .setNeutralButton("同步状态") { _, _ ->
                    showDialog(
                        AlertDialog.Builder(activity)
                            .setTitle("账号同步状态")
                            .setMessage(
                                "$status\n\n观看历史仅请求最近50部，预约每类首批20部；收藏只覆盖本次接口返回。分页请求协议尚未验证，不能保证完整同步，也不能根据未显示推断账户没有该记录。\n\n登录后，电视播放的进度会自动上传。手机记录可从「云端继续观看」打开。平台接口可能变化，首次使用请对照手机核对集数和时间。\n本机历史与云端记录分别保留；退出登录会停止上传。"
                            )
                            .setPositiveButton("关闭", null)
                    )
                }
                .setNegativeButton("关闭", null)
        )
    }

    private fun input(
        title: String,
        numeric: Boolean,
        error: String? = null,
        submit: (String) -> Unit,
    ) {
        val field =
            EditText(activity).apply {
                isSingleLine = true
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_PHONE
            }
        val builder =
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(field)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认", null)
        if (error != null) builder.setMessage(error)
        showDialog(builder)
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val value = field.text.toString().trim()
            if (value.isBlank()) return@setOnClickListener
            dialog?.dismiss()
            submit(value)
        }
    }

    private fun phone() {
        input("手机号（确认后发送登录验证码）", false) { raw ->
            val mobile =
                runCatching { PhoneLogin.parse(raw) }
                    .getOrElse {
                        toast(safeError(it))
                        return@input
                    }
            val login = WebLogin(http)
            task(
                { login.send(mobile) },
                { sent -> code(login, sent.maskedMobile) },
                { failure ->
                    showDialog(
                        AlertDialog.Builder(activity)
                            .setTitle("短信登录未完成")
                            .setMessage(safeError(failure))
                            .setPositiveButton("改用扫码") { _, _ -> qrLogin() }
                            .setNegativeButton("返回", null)
                    )
                },
            )
        }
    }

    private fun code(login: WebLogin, masked: String, error: String? = null) {
        input("输入发送至 $masked 的验证码", true, error) { value ->
            if (!value.matches(Regex("[0-9]{4,8}"))) {
                code(login, masked, "请输入 4—8 位验证码")
                return@input
            }
            task(
                { validateAccount(login.verify(value)).also { store.save(it.first,it.second) } },
                { (next, account) -> acceptAccount(next, account) },
                { failure ->
                    if (failure is PhoneLogin.Failure && !failure.canRetryCode) {
                        showDialog(
                            AlertDialog.Builder(activity)
                                .setTitle("登录未完成")
                                .setMessage(safeError(failure))
                                .setPositiveButton("返回账号页") { _, _ -> show() }
                                .setNeutralButton("改用扫码") { _, _ -> qrLogin() }
                        )
                    } else code(login, masked, safeError(failure))
                },
            )
        }
    }

    private fun validateAccount(web: AccountCredentials): Pair<AccountCredentials, CloudProfile> {
        val device = client.registerDevice() ?: throw java.io.IOException("账号设备初始化失败")
        val next = web.copy(deviceId = device.deviceId, installId = device.installId)
        val account = client.profile(next)
        return next to account
    }

    private fun acceptAccount(next: AccountCredentials, account: CloudProfile) {
        clearSession("已登录，正在读取云端记录")
        credentials=next;profile=account;selectedAccountId=account.id;sessionVerified=true
        vault=vault.copy(accounts=listOf(StoredAccount(account,next,System.currentTimeMillis()))+vault.accounts.filterNot{it.profile.id==account.id},
            activeId=account.id,legacy=vault.legacy?.takeUnless{it.cookie==next.cookie},legacyActive=false)
        changed();toast("已登录 ${account.nickname}");refresh(true)
    }

    private fun qrLogin() {
        val login = WebLogin(http)
        task(
            { login.qr() },
            { qr ->
                val bitmap =
                    android.graphics.BitmapFactory.decodeByteArray(qr.image, 0, qr.image.size)
                if (bitmap == null) {
                    toast("登录二维码无法显示，请重试")
                    return@task
                }
                val size = (300 * activity.resources.displayMetrics.density).toInt()
                val image =
                    android.widget.ImageView(activity).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                        setBackgroundColor(android.graphics.Color.WHITE)
                        layoutParams =
                            android.widget.LinearLayout.LayoutParams(size, size).apply {
                                gravity = android.view.Gravity.CENTER_HORIZONTAL
                            }
                    }
                val panel =
                    android.widget.LinearLayout(activity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(24, 16, 24, 16)
                        addView(image)
                        addView(
                            android.widget.TextView(activity).apply {
                                text = "用手机红果 App：我的 → 右上角菜单 → 扫一扫\n扫码后请在手机确认登录"
                                textSize = 18f
                                gravity = android.view.Gravity.CENTER
                                setPadding(0, 12, 0, 0)
                            }
                        )
                    }
                showDialog(
                    AlertDialog.Builder(activity)
                        .setTitle("红果官方扫码登录")
                        .setView(panel)
                        .setNegativeButton("取消", null)
                )
                val current = dialog ?: return@task
                val token = generation
                val deadline =
                    android.os.SystemClock.elapsedRealtime() + qr.expiresIn.coerceIn(1, 300) * 1000L
                var cancelled = false
                busy = true
                status = "等待手机确认扫码登录"
                current.setOnDismissListener {
                    cancelled = true
                    login.cancel()
                    if (token == generation) {
                        busy = false
                        status = "已取消扫码登录"
                    }
                }
                // Android dispatches OnDismiss through a Handler. Finish the QR operation now;
                // a delayed dismissal must not keep the save task busy or cancel its successor.
                fun closeQr() {
                    cancelled = true
                    current.setOnDismissListener(null)
                    login.cancel()
                    if (token == generation) busy = false
                    current.dismiss()
                }
                fun poll() {
                    if (cancelled || closed || token != generation) return
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        closeQr()
                        status = "二维码已过期，请重新扫码"
                        showDialog(
                            AlertDialog.Builder(activity)
                                .setTitle("二维码已过期")
                                .setMessage("请刷新二维码，再用手机红果 App 扫码确认。")
                                .setPositiveButton("刷新二维码") { _, _ -> qrLogin() }
                                .setNegativeButton("取消", null)
                        )
                        return
                    }
                    worker.execute {
                        val result = runCatching {
                            if (login.poll()) validateAccount(login.credentials()) else null
                        }
                        main.post {
                            if (cancelled || closed || token != generation) return@post
                            result
                                .onSuccess { account ->
                                    if (account != null) {
                                        closeQr()
                                        status = "扫码已确认，正在保存登录状态"
                                        task(
                                            {
                                                store.save(account.first,account.second)
                                                account
                                            },
                                            { acceptAccount(it.first, it.second) },
                                            { failure ->
                                                showDialog(
                                                    AlertDialog.Builder(activity)
                                                        .setTitle("登录状态保存失败")
                                                        .setMessage(safeError(failure))
                                                        .setPositiveButton("重新扫码") { _, _ ->
                                                            qrLogin()
                                                        }
                                                        .setNegativeButton("返回账号页") { _, _ ->
                                                            show()
                                                        }
                                                )
                                            },
                                        )
                                    } else main.postDelayed({ poll() }, 2000)
                                }
                                .onFailure {
                                    closeQr()
                                    status = safeError(it)
                                    showDialog(
                                        AlertDialog.Builder(activity)
                                            .setTitle("扫码登录未完成")
                                            .setMessage(status)
                                            .setPositiveButton("重新扫码") { _, _ -> qrLogin() }
                                            .setNegativeButton("返回账号页") { _, _ -> show() }
                                    )
                                }
                        }
                    }
                }
                main.postDelayed({ poll() }, 1500)
            },
        )
    }

    private fun <T> task(
        action: () -> T,
        success: (T) -> Unit,
        failure: (Throwable) -> Unit = { toast(safeError(it)) },
    ) {
        if (busy || closed) return
        busy = true
        val token = generation
        toast("正在连接红果账号…")
        worker.execute {
            if (closed || token != generation) return@execute
            val result = runCatching(action)
            main.post {
                if (closed || token != generation) return@post
                busy = false
                result.onSuccess(success).onFailure {
                    status = safeError(it)
                    failure(it)
                }
            }
        }
    }

    private data class Refreshed(val profile:CloudProfile,val page:Result<CloudListPage<CloudProgress>>,val vault:AccountVault)
    private fun refresh(showResult: Boolean) {
        val account=credentials ?: return
        if(busy || closed)return
        val expected=selectedAccountId;val token=generation
        status="正在验证账号并读取观看历史…"
        task({
            val user=client.profile(account)
            if(expected!=null && user.id!=expected)throw CloudAuthenticationException("当前登录与所选账号不一致，请重新登录")
            if(closed || token!=generation)throw java.util.concurrent.CancellationException()
            val saved=store.save(account,user)
            Refreshed(user,runCatching{client.historyPage(account)},saved)
        },{ result->
            profile=result.profile;selectedAccountId=result.profile.id;sessionVerified=true;vault=result.vault
            result.page.onSuccess {page->
                historyLoaded=true;history=page.items.distinctBy{it.series.id}.sortedByDescending{it.updatedAt}.take(50)
                status="已读取 ${history.size} 部；${page.coverage.message}"
            }.onFailure {status="账号已验证，观看历史读取失败，可重试"}
            changed();refreshFavorites();refreshReservations()
            if(showResult){toast(status);show()}
        },{error->
            if(error is CloudAuthenticationException){
                val selectedCredentials=credentials;val selectedProfile=profile;val selected=selectedAccountId
                clearSession(safeError(error));credentials=selectedCredentials;profile=selectedProfile;selectedAccountId=selected
            }
            changed();toast(safeError(error))
        })
        changed()
    }

    private fun showHistory() {
        if (history.isEmpty()) {
            toast("暂无已读取的云端记录，请先刷新")
            return
        }
        val snapshot = history
        showDialog(
            AlertDialog.Builder(activity)
                .setTitle("云端继续观看")
                .setItems(
                    snapshot
                        .map {
                            "${it.series.title} · 第 ${it.episodeIndex+1} 集 · ${it.position/1000} 秒"
                        }
                        .toTypedArray()
                ) { _, index ->
                    play(snapshot[index])
                }
                .setNegativeButton("关闭", null)
        )
    }

    private fun logout() {
        val id=selectedAccountId
        selectSaved {if(id!=null)store.remove(id) else store.forgetLegacy()}
    }

    fun upload(progress: CloudProgress, episodes: Int, force: Boolean) {
        val account = credentials?.takeIf { profile != null && sessionVerified } ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && progress.episodeId == lastEpisode && now - lastUpload < 30000) return
        lastUpload = now
        lastEpisode = progress.episodeId
        history =
            (listOf(progress.copy(episodeCount = episodes)) +
                    history.filterNot { it.series.id == progress.series.id })
                .take(50)
        changed()
        pending = progress to episodes
        lastProgress = pending
        if (draining) return
        draining = true
        drain(account, generation)
    }

    private fun drain(account: AccountCredentials, token: Int) {
        val next = pending
        if (closed || token != generation) return
        if (next == null) {
            draining = false
            return
        }
        pending = null
        worker.execute {
            val result = runCatching {
                if (!closed && token == generation) client.upload(account, next.first, next.second)
            }
            main.post {
                if (closed) return@post
                if (token == generation)
                    status =
                        if (result.isSuccess) "播放进度已上传（请在手机端核对续播）"
                        else "进度上传失败：${safeError(result.exceptionOrNull()!!)}"
                drain(account, token)
            }
        }
    }

    fun destroy() {
        val account = credentials?.takeIf {sessionVerified}
        val final = lastProgress
        onHistoryChanged = null
        closed = true
        generation++
        pending = null
        dialog?.dismiss()
        http.dispatcher.cancelAll()
        worker.execute {
            if (account != null && final != null)
                runCatching { client.upload(account, final.first, final.second) }
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
        worker.shutdown()
    }
}
