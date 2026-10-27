// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Window
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hongguotv.core.LibraryBackup
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Coordinates validated portable partitions. Uploading only stages a TV-side restore preview. */
class CompleteBackupController(private val activity: Activity, private val library: Library,
    private val preferences: FeaturePreferences, private val appearance: AppearanceController,
    private val afterRestore: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val source = SourceLibraryBackup(activity)
    @Volatile private var transfer: PortableTransferServer? = null
    private var dialog: Dialog? = null
    @Volatile private var destroyed = false

    private fun dp(value: Int) = (activity.resources.displayMetrics.density * value).toInt()
    private fun text(value: String, size: Float = 17f) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(TvStyle.text); setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12)); setBackgroundColor(TvStyle.background)
    }
    private fun track(next: Dialog) {
        dialog = next
        next.setOnDismissListener {
            if (dialog === next) {
                dialog = null; generation.incrementAndGet(); transfer?.close(); transfer = null
            }
        }
    }
    private fun info(ticket: Int, title: String, message: String) {
        if (destroyed || generation.get() != ticket) return
        val old = dialog; dialog = null; old?.dismiss()
        val next = AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("关闭", null).create()
        track(next); next.show()
    }

    fun show(includeExtras: Boolean) {
        if (destroyed) return
        close()
        if (!library.isLoaded) {
            info(generation.get(), "备份尚未就绪", "本机记录仍在读取，请稍后重试。")
            return
        }
        val snapshot = library.snapshot() // The library exposes immutable memory snapshots on UI.
        val ticket = generation.get()
        val body = column()
        body.addView(text(if (includeExtras) "完整备份与恢复" else "记录备份与恢复", 23f))
        val status = text("正在准备备份…", 15f)
        body.addView(status)
        val qr = ImageView(activity).apply { contentDescription = "备份与恢复二维码"; setBackgroundColor(Color.WHITE) }
        body.addView(qr, LinearLayout.LayoutParams(dp(240), dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        body.addView(text(if (includeExtras) "含红果记录、工具偏好、片源配置及第三方记录、外观与弹幕偏好。片源接口可能含私人凭据，请妥善保管。" else "含红果记录与本机工具偏好；不包含私人片源接口、背景图片、账号登录或设备标识。", 14f))
        body.addView(text("手机与电视需要在同一局域网。下载仅一次；上传需在电视确认。关闭即失效，最长 5 分钟。", 14f))
        body.addView(Button(activity).apply { text = "关闭"; setOnClickListener { close() } })
        val next = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(ScrollView(activity).apply { addView(body) })
        }
        track(next); next.show()
        next.window?.setLayout(minOf(dp(720), (activity.resources.displayMetrics.widthPixels * .9).toInt()),
            minOf(dp(680), (activity.resources.displayMetrics.heightPixels * .9).toInt()))
        worker.submit {
            if (destroyed || generation.get() != ticket) return@submit
            runCatching {
                val raw = PortableBackupCodec.encode(LibraryBackup.encode(snapshot), preferences.export(), System.currentTimeMillis(),
                    if (includeExtras) source.exportState() else null, if (includeExtras) appearance.exportState() else null)
                val addresses = LanInputAddresses.find(activity.getSystemService(ConnectivityManager::class.java))
                check(addresses.isNotEmpty()) { "未找到 Wi-Fi 或网线局域网地址，请检查电视网络。" }
                if (destroyed || generation.get() != ticket) return@submit
                val server = PortableTransferServer(addresses.first().address, raw, { decodeRaw(it) }, { imported ->
                    // This callback is a socket worker: large image validation must stay off UI.
                    val incoming = decodeRaw(imported)
                    main.post { if (!destroyed && generation.get() == ticket) preview(incoming) }
                }, {
                    main.post { if (!destroyed && generation.get() == ticket && next.isShowing) status.text = "二维码已过期，请关闭后重新打开。" }
                })
                transfer = server
                if (destroyed || generation.get() != ticket) { server.close(); transfer = null; return@submit }
                val bits = QRCodeWriter().encode(server.url, BarcodeFormat.QR_CODE, 360, 360)
                val pixels = IntArray(360 * 360) { if (bits[it % 360, it / 360]) Color.BLACK else Color.WHITE }
                val bitmap = Bitmap.createBitmap(pixels, 360, 360, Bitmap.Config.ARGB_8888)
                main.post {
                    if (!destroyed && generation.get() == ticket && next.isShowing) {
                        qr.setImageBitmap(bitmap); status.text = "手机扫码下载备份，或上传文件到电视确认。\n${server.url}"
                    }
                }
            }.onFailure { e -> main.post { info(ticket, "无法准备备份", e.message ?: "请检查存储空间和局域网连接。") } }
        }
    }

    fun preview(incoming: LocalFeatureBackup) {
        if (destroyed) return
        close()
        val ticket = generation.get()
        worker.submit {
            if (destroyed || generation.get() != ticket) return@submit
            runCatching { validate(incoming) }.onSuccess { safe ->
                main.post { if (!destroyed && generation.get() == ticket) previewValidated(safe, ticket) }
            }.onFailure { e -> main.post { info(ticket, "备份无法恢复", "${e.message ?: "备份校验失败"}\n\n尚未更改本机记录或偏好。") } }
        }
    }

    private fun validate(incoming: LocalFeatureBackup): LocalFeatureBackup {
        // Re-encoding also validates callers that supplied a LocalFeatureBackup directly.
        val safe = PortableBackupCodec.decode(PortableBackupCodec.encode(LibraryBackup.encode(incoming.data), incoming.tools,
            incoming.savedAt, incoming.sources, incoming.appearance))
        safe.sources?.let(source::validate) // Includes existing+incoming source capacity, before ANY write.
        return safe
    }

    private fun previewValidated(safe: LocalFeatureBackup, ticket: Int) {
        if (!library.isLoaded) { info(ticket, "暂时无法恢复", "本机记录尚未就绪，请稍后重试。"); return }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(10)) }
        val stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(safe.savedAt))
        body.addView(TextView(activity).apply {
            text = "$stamp\n${safe.data.favorites.size} 部收藏，${safe.data.history.size} 条观看记录。\n" +
                (if (safe.sources != null) "含片源配置及第三方收藏/历史。\n" else "") +
                (if (safe.appearance != null) "含外观及可用的弹幕偏好。\n" else "") +
                "\n收藏合并，观看进度保留较新的记录；片头片尾和收藏分组补充缺少条目。账号登录和设备标识保持不变。"
            textSize = 17f
        })
        val options = CheckBox(activity).apply {
            text = "恢复播放 / 显示偏好（含外观与弹幕）"
            isChecked = false; textSize = 17f; minHeight = dp(48)
        }
        body.addView(options)
        val status = TextView(activity).apply { textSize = 15f }
        body.addView(status)
        val next = AlertDialog.Builder(activity).setTitle("合并恢复备份")
            .setView(ScrollView(activity).apply { addView(body) }).setNegativeButton("取消", null)
            .setPositiveButton("确认合并", null).create()
        track(next); next.show()
        next.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { close() }
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val restoreOptions = options.isChecked
            next.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            next.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            options.isEnabled = false
            next.setCancelable(false)
            status.text = "正在重新校验并合并…"
            worker.submit {
                if (destroyed || generation.get() != ticket) return@submit
                val result = runCatching { validate(safe) }
                main.post {
                    if (destroyed || generation.get() != ticket) return@post
                    result.onSuccess { checked ->
                        startConfirmedRestore(checked, restoreOptions, ticket)
                    }.onFailure { error ->
                        info(ticket, "备份无法恢复", (error.message ?: "恢复失败，请检查备份文件与存储空间。") + "\n\n尚未更改本机记录或偏好。")
                    }
                }
            }
        }
        next.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    private fun startConfirmedRestore(checked: LocalFeatureBackup, restoreOptions: Boolean, ticket: Int) {
        // All partitions were validated on the worker. This first mutation is one main-thread
        // turn: onDestroy cannot close the original library between its merge and flush.
        // A replacement Activity then waits for the original retired writes and loads this merge.
        val merged = runCatching {
            val tools = if (restoreOptions && checked.appearance != null)
                JSONObject(checked.tools).apply { remove("theme") }.toString() else checked.tools
            preferences.restore(tools, restoreOptions)
            library.restore(checked.data, restoreOptions)
            library.flush()
        }
        if (merged.isFailure) {
            info(ticket, "恢复未完成", "请保留备份文件并重试。已合并的记录保留，恢复不会覆盖较新的观看进度。")
            return
        }
        // Queue the already-confirmed private IO before returning to the event loop. destroy()
        // uses shutdown(), so this job can finish while unconfirmed/queued previews are cancelled.
        worker.submit {
            val result = runCatching {
                if (restoreOptions) checked.appearance?.let(appearance::restoreConfirmedState)
                checked.sources?.let(source::restoreState)
            }
            main.post {
                if (destroyed) {
                    if (result.isFailure) android.util.Log.e("HongguoTV", "Confirmed backup optional-partition restore did not complete")
                    return@post
                }
                afterRestore()
                if (generation.get() == ticket) result.onSuccess {
                    info(ticket, "恢复完成", "已合并备份。现有较新的观看进度保留。")
                }.onFailure {
                    info(ticket, "恢复未完成", "红果记录已合并，部分片源或外观写入未完成。请保留备份并重试。")
                }
            }
        }
    }

    private fun decodeRaw(raw: String): LocalFeatureBackup = if (JSONObject(raw).optString("format") == "hongguotv.library")
        LocalFeatureBackup(LibraryBackup.decode(raw), "{\"version\":1}", JSONObject(raw).optLong("createdAt").coerceAtLeast(0))
    else PortableBackupCodec.decode(raw)

    fun close() {
        generation.incrementAndGet(); transfer?.close(); transfer = null
        val old = dialog; dialog = null; old?.dismiss()
    }
    fun destroy() { destroyed = true; close(); worker.shutdown() }
}
