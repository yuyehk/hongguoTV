// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Dialog
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Owns only theme/background state. No library, source, account or device data is read or written. */
class AppearanceController(private val activity: Activity, private val onChanged: () -> Unit,
    private val chooseImage: () -> Unit) {
    private val preferences = FeaturePreferences(activity)
    private val social = SocialPreferences(activity)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val imageRevision = AtomicInteger()
    private val storageLock = Any()
    private val imageFile = AtomicFile(File(activity.filesDir, "appearance-v1/background.jpg"))
    @Volatile private var destroyed = false
    private var image: Bitmap? = null
    private var dialog: Dialog? = null
    @Volatile private var transfer: AppearanceTransferServer? = null

    private fun dp(n: Int) = (activity.resources.displayMetrics.density * n).toInt()
    private fun text(value: String, size: Float = 17f) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(TvStyle.text)
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }
    private fun button(value: String, action: () -> Unit) = text(value).apply {
        isFocusable = true; isFocusableInTouchMode = true; minHeight = dp(48)
        background = TvStyle.card(activity, false)
        setOnFocusChangeListener { _, focused -> background = TvStyle.card(activity, focused) }
        setOnClickListener { action() }
    }
    private fun body() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14)); setBackgroundColor(TvStyle.background)
    }
    private fun open(title: String, body: LinearLayout): Dialog {
        val next = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(ScrollView(activity).apply { addView(body) })
            setOnDismissListener {
                if (dialog === this) { dialog = null; generation.incrementAndGet(); transfer?.close(); transfer = null }
            }
        }
        dialog = next
        next.show()
        next.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(minOf(dp(760), (activity.resources.displayMetrics.widthPixels * .9f).toInt()),
                minOf(dp(650), (activity.resources.displayMetrics.heightPixels * .9f).toInt()))
        }
        body.contentDescription = title
        return next
    }

    fun show() {
        if (destroyed) return
        close()
        val body = body()
        body.addView(text("外观 · ${preferences.theme.label}", 23f))
        body.addView(text("选择配色，或使用自己的图片作为页面背景。观看记录保持不变。", 14f))
        var first: View? = null
        FeatureTheme.entries.chunked(4).forEach { group ->
            val row = LinearLayout(activity)
            group.forEach { theme ->
                val item = button((if (preferences.theme == theme) "✓ " else "") + theme.label) {
                    preferences.theme = theme; TvStyle.apply(theme); onChanged(); show()
                }
                if (first == null) first = item
                row.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            body.addView(row)
        }
        body.addView(button("从本机选择图片") { close(); chooseImage() })
        body.addView(button("手机扫码上传背景") { phoneUpload() })
        body.addView(button("移除背景图片") {
            resetImage()
        })
        body.addView(button("恢复默认外观") {
            resetImage(FeatureTheme.DARK)
        })
        body.addView(button("关闭") { close() })
        open("外观", body)
        first?.requestFocus()
    }

    /** Called by the root's document picker callback; URI access and decoding stay on the worker. */
    fun onImagePicked(uri: Uri) {
        if (destroyed) return
        close()
        val ticket = generation.incrementAndGet()
        worker.submit {
            runCatching {
                val bytes = activity.contentResolver.openInputStream(uri)?.use(AppearanceImages::readBounded)
                    ?: error("无法读取图片")
                val prepared = AppearanceImages.prepare(bytes)
                savePrepared(prepared, ticket)
            }.onFailure { message(ticket, it.message ?: "图片导入失败") }
        }
    }

    private fun message(ticket: Int, value: String) = main.post {
        if (!destroyed && generation.get() == ticket) Toast.makeText(activity, value, Toast.LENGTH_LONG).show()
    }

    private fun writeImage(bytes: ByteArray?) {
        if (bytes == null) { imageFile.delete(); return }
        imageFile.baseFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "无法创建背景目录" } }
        val output = imageFile.startWrite()
        try { output.write(bytes); imageFile.finishWrite(output) }
        catch (e: Exception) { imageFile.failWrite(output); throw e }
    }

    private fun savePrepared(bytes: ByteArray, ticket: Int) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("图片无法解码")
        synchronized(storageLock) {
            check(!destroyed && generation.get() == ticket) { "导入已取消" }
            writeImage(bytes)
            imageRevision.incrementAndGet()
        }
        main.post {
            if (!destroyed && generation.get() == ticket) {
                image = bitmap
                onChanged()
                Toast.makeText(activity, "背景图片已保存", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resetImage(theme: FeatureTheme? = null) {
        close()
        val ticket = generation.incrementAndGet()
        worker.submit {
            runCatching {
                synchronized(storageLock) {
                    if (generation.get() != ticket || destroyed) return@submit
                    writeImage(null)
                    imageRevision.incrementAndGet()
                    if (theme != null) preferences.theme = theme
                }
                main.post {
                    if (!destroyed && generation.get() == ticket) {
                        image = null; TvStyle.apply(preferences.theme); onChanged(); show()
                    }
                }
            }.onFailure { message(ticket, "背景移除失败，请重试") }
        }
    }

    /** Uses cached pixels only. The caller first assigns its normal base background. */
    fun apply(rootView: View) {
        val bitmap = image ?: return
        rootView.background = BackgroundDrawable(bitmap, TvStyle.isLight)
    }

    fun loadAsync() {
        if (destroyed) return
        val revision = imageRevision.get()
        worker.submit {
            val bitmap = synchronized(storageLock) {
                runCatching {
                    if (!imageFile.baseFile.exists()) null
                    else imageFile.openRead().use(AppearanceImages::readBounded).let { bytes ->
                        AppearanceImages.validateStored(bytes)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }.getOrNull()
            }
            main.post {
                if (!destroyed && imageRevision.get() == revision) { image = bitmap; onChanged() }
            }
        }
    }

    private fun phoneUpload() {
        close()
        val body = body()
        body.addView(text("手机上传背景", 23f))
        val status = text("正在查找局域网地址…", 15f)
        body.addView(status)
        val qr = ImageView(activity).apply { contentDescription = "背景上传二维码"; setBackgroundColor(Color.WHITE) }
        body.addView(qr, LinearLayout.LayoutParams(dp(250), dp(250)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        body.addView(button("关闭") { close() })
        val d = open("手机上传背景", body)
        val ticket = generation.get()
        worker.submit {
            runCatching {
                val addresses = LanInputAddresses.find(activity.getSystemService(ConnectivityManager::class.java))
                check(addresses.isNotEmpty()) { "未找到 Wi-Fi 或网线地址，请让手机与电视连接同一局域网。" }
                val server = AppearanceTransferServer(addresses.first().address, { bytes ->
                    val prepared = AppearanceImages.prepare(bytes)
                    savePrepared(prepared, ticket)
                }, {
                    if (generation.compareAndSet(ticket, ticket + 1)) main.post {
                        if (d.isShowing) status.text = "二维码已过期，请关闭后重新打开。"
                    }
                }, onCompleted = {
                    main.post {
                        if (!destroyed && generation.get() == ticket && d.isShowing) {
                            transfer = null; d.dismiss()
                        }
                    }
                })
                if (destroyed || generation.get() != ticket) { server.close(); return@submit }
                transfer = server
                // Close can race initialization between the check and assignment.
                if (destroyed || generation.get() != ticket) { server.close(); transfer = null; return@submit }
                val bits = QRCodeWriter().encode(server.url, BarcodeFormat.QR_CODE, 360, 360)
                val pixels = IntArray(360 * 360) { if (bits[it % 360, it / 360]) Color.BLACK else Color.WHITE }
                val bitmap = Bitmap.createBitmap(pixels, 360, 360, Bitmap.Config.ARGB_8888)
                main.post {
                    if (generation.get() == ticket && d.isShowing) {
                        qr.setImageBitmap(bitmap)
                        status.text = "手机扫描二维码选择图片。最多 8 MB / 1600 万像素；最长 5 分钟。\n${server.url}"
                    }
                }
            }.onFailure { e -> main.post { if (generation.get() == ticket && d.isShowing) status.text = e.message ?: "暂时无法开启上传" } }
        }
    }

    /** Synchronous file operations for the root backup worker, never for UI rendering. */
    fun exportState(): String = synchronized(storageLock) {
        val bytes = if (imageFile.baseFile.exists()) imageFile.openRead().use(AppearanceImages::readBounded) else null
        if (bytes != null) AppearanceImages.validateStored(bytes)
        JSONObject().put("version", 2).put("theme", preferences.theme.name).put("social", social.exportState())
            .put("image", bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }.orEmpty()).toString()
    }

    fun restoreState(raw: String) {
        restore(raw, false)
    }

    /** A TV-confirmed portable merge can finish private writes after its Activity is destroyed. */
    internal fun restoreConfirmedState(raw: String) {
        restore(raw, true)
    }

    private fun restore(raw: String, confirmed: Boolean) {
        val state = decodeState(raw) // Entire partition validated before file or preference changes.
        val bitmap = state.image?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        val ticket = generation.incrementAndGet()
        transfer?.close(); transfer = null
        synchronized(storageLock) {
            check(confirmed || !destroyed)
            writeImage(state.image)
            imageRevision.incrementAndGet()
            preferences.theme = state.theme
            state.social?.let(social::restoreState)
        }
        main.post {
            if (!destroyed && generation.get() == ticket) { image = bitmap; TvStyle.apply(preferences.theme); onChanged() }
        }
    }

    fun close() {
        generation.incrementAndGet()
        transfer?.close(); transfer = null
        val d = dialog; dialog = null; d?.dismiss()
    }
    fun destroy() { destroyed = true; close(); worker.shutdownNow(); image = null }

    companion object {
        fun validateState(raw: String) { decodeState(raw) }
        private data class State(val theme: FeatureTheme, val image: ByteArray?, val social: JSONObject?)
        private fun decodeState(raw: String): State {
            require(raw.length <= AppearanceImages.MAX_STORED * 4 / 3 + 100_000) { "外观备份过大" }
            val json = JSONObject(raw)
            val version = json.getInt("version")
            require(version in 1..2 && json.length() == if (version == 1) 3 else 4) { "外观备份格式无效" }
            require(json.get("version") == version) { "外观备份版本无效" }
            require(json.get("theme") is String && json.get("image") is String && json.get("version") is Number) { "外观备份字段无效" }
            val social = if (version == 2) json.getJSONObject("social").also(SocialPreferences::validateState) else null
            val theme = FeatureTheme.entries.firstOrNull { it.name == json.getString("theme") } ?: error("未知配色")
            val encoded = json.getString("image")
            val bytes = if (encoded.isEmpty()) null else {
                require(encoded.length <= (AppearanceImages.MAX_STORED + 2) / 3 * 4 && encoded.matches(Regex("[A-Za-z0-9+/]*={0,2}"))) { "背景编码无效" }
                Base64.decode(encoded, Base64.NO_WRAP).also(AppearanceImages::validateStored)
            }
            return State(theme, bytes, social)
        }
    }

    private class BackgroundDrawable(private val bitmap: Bitmap, private val light: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty || bitmap.isRecycled) return
            val scale = maxOf(bounds.width().toFloat() / bitmap.width, bounds.height().toFloat() / bitmap.height)
            val w = bitmap.width * scale; val h = bitmap.height * scale
            val left = bounds.exactCenterX() - w / 2; val top = bounds.exactCenterY() - h / 2
            canvas.save(); canvas.clipRect(bounds)
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), paint)
            canvas.drawColor(if (light) Color.argb(204, 255, 255, 255) else Color.argb(190, 0, 0, 0))
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
    }
}
