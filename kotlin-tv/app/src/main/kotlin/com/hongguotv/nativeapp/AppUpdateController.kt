// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.hongguotv.core.RequestScope
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

internal class AppUpdateController(
    private val activity: Activity,
    private val source: AppUpdateSource = AppUpdateSource(),
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private val directory = File(activity.cacheDir, "app-updates")
    private val checkHistory = activity.getSharedPreferences("app-update-check", Activity.MODE_PRIVATE)
    private var active: RequestScope? = null
    private var backgroundCheck: RequestScope? = null
    private var pendingUpdate: AppUpdate? = null
    private var dialog: AlertDialog? = null
    private var awaitingPermission: File? = null
    private var destroyed = false

    fun checkOnOpen(canPrompt: () -> Boolean) {
        if (destroyed || active != null || backgroundCheck != null ||
            awaitingPermission != null || dialog?.isShowing == true) return
        val scope = RequestScope()
        backgroundCheck = scope
        worker.execute {
            val outcome = runCatching { scope.run { checkVersion() } }
            activity.runOnUiThread {
                if (destroyed || backgroundCheck !== scope) return@runOnUiThread
                backgroundCheck = null
                recordCheck(outcome)
                if (outcome.isSuccess) pendingUpdate = outcome.getOrNull()
                showPendingUpdate(canPrompt)
            }
        }
    }

    fun showPendingUpdate(canPrompt: () -> Boolean) {
        val release = pendingUpdate ?: return
        if (destroyed || activity.isFinishing || activity.isDestroyed ||
            active != null || awaitingPermission != null || dialog?.isShowing == true || !canPrompt()) return
        pendingUpdate = null
        offer(release)
    }

    fun cancelBackgroundCheck() {
        backgroundCheck?.cancel()
        backgroundCheck = null
    }

    fun show() {
        if (active != null || destroyed) return
        cancelBackgroundCheck()
        pendingUpdate = null
        runTask("正在检查更新…", { checkVersion() }, onResult = ::recordCheck) { release ->
            if (release == null) {
                info("当前版本 ${BuildConfig.VERSION_NAME} 已是最新。")
            } else {
                offer(release)
            }
        }
    }

    private fun checkVersion(): AppUpdate? {
        try {
            return source.check(BuildConfig.VERSION_NAME)
        } catch (error: IOException) {
            val temporary = when (error) {
                is UpdateHttpException -> error.statusCode in setOf(408, 500, 502, 503, 504)
                is SocketTimeoutException, is SocketException, is UnknownHostException -> true
                else -> false
            }
            if (!temporary) throw error
            RequestScope.ensureActive()
            Thread.sleep(500)
            RequestScope.ensureActive()
            return source.check(BuildConfig.VERSION_NAME)
        }
    }

    private fun recordCheck(outcome: Result<AppUpdate?>) {
        val result = outcome.fold(
            onSuccess = { release -> release?.let { "发现新版 ${it.versionName}" } ?: "已是最新" },
            onFailure = { error ->
                when (error) {
                    is UpdateHttpException -> "失败（HTTP ${error.statusCode}）"
                    is SocketTimeoutException -> "失败（连接或读取超时）"
                    is UnknownHostException -> "失败（域名解析失败）"
                    is SocketException -> "失败（网络连接中断）"
                    else -> "失败（${error.javaClass.simpleName}）"
                }
            },
        )
        checkHistory.edit().putLong("time", System.currentTimeMillis()).putString("result", result).apply()
        Log.i("HongguoTV", "Update check: $result")
    }

    fun diagnostics(): String {
        val time = checkHistory.getLong("time", 0L)
        if (time == 0L) return "当前版本 ${BuildConfig.VERSION_NAME}\n尚无更新检查结果。"
        val whenChecked = DateFormat.getDateTimeInstance().format(Date(time))
        return "当前版本 ${BuildConfig.VERSION_NAME}\n最近检查：$whenChecked\n结果：${checkHistory.getString("result", "")}"
    }

    private fun offer(release: AppUpdate) {
        confirm("发现新版 ${release.versionName}", "当前版本 ${BuildConfig.VERSION_NAME}\n更新时应用会关闭。",
            "下载并安装") { download(release) }
    }

    private fun download(release: AppUpdate) {
        runTask("正在下载安装包…", { progress ->
            directory.listFiles()?.forEach { it.delete() }
            val file = source.download(release, directory, progress)
            try {
                val pm = activity.packageManager
                @Suppress("DEPRECATION")
                val installed = pm.getPackageInfo(activity.packageName, signingFlag()).updateIdentity()
                @Suppress("DEPRECATION")
                val candidate = pm.getPackageArchiveInfo(file.absolutePath, signingFlag())?.updateIdentity()
                    ?: throw IOException("无法读取安装包或签名。")
                validateUpdateApk(installed, candidate, release.versionName)
                file
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }, done = ::install)
    }

    @Suppress("DEPRECATION")
    private fun install(file: File) {
        if (destroyed) return
        if (!file.isFile) { info("更新文件已被系统清理，请重新下载。"); return }
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            confirm("允许安装应用", "请为红果 TV 开启「允许安装未知应用」，返回后继续安装。", "去授权") {
                try {
                    awaitingPermission = file
                    activity.startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                        PERMISSION_REQUEST,
                    )
                } catch (_: ActivityNotFoundException) {
                    awaitingPermission = null
                    info("电视未提供授权页面。请在系统设置中允许红果 TV 安装应用后重试。")
                } catch (_: SecurityException) {
                    awaitingPermission = null
                    info("电视系统限制了安装授权。")
                }
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.app-updates", file)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("APK", uri) }
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            info("电视没有可用的系统安装器。")
        } catch (_: SecurityException) {
            info("电视系统限制了应用内安装。")
        }
    }

    fun onActivityResult(requestCode: Int): Boolean {
        if (requestCode != PERMISSION_REQUEST) return false
        val file = awaitingPermission ?: return true
        awaitingPermission = null
        if (Build.VERSION.SDK_INT >= 26 && activity.packageManager.canRequestPackageInstalls()) install(file)
        else info("尚未取得安装权限，可以稍后再试。")
        return true
    }

    private fun <T> runTask(
        title: String,
        task: ((Int) -> Unit) -> T,
        onResult: (Result<T>) -> Unit = {},
        done: (T) -> Unit,
    ) {
        val scope = RequestScope()
        active = scope
        val progress = AlertDialog.Builder(activity).setTitle(title).setMessage("请稍候")
            .setNegativeButton("取消", null).create()
        progress.setOnDismissListener {
            if (active === scope) { active = null; scope.cancel() }
        }
        showDialog(progress)
        worker.execute {
            val outcome = runCatching {
                scope.run {
                    task { percent ->
                        activity.runOnUiThread { if (active === scope) progress.setMessage("下载进度 $percent%") }
                    }
                }
            }
            activity.runOnUiThread {
                if (destroyed || active !== scope) return@runOnUiThread
                active = null
                progress.dismiss()
                onResult(outcome)
                outcome.fold(done) { error ->
                    // Do not show network URLs, tokens, or exception details on screen.
                    info(error.message?.takeIf { it.endsWith("。") && error is IOException }
                        ?: "检查或下载失败，请稍后重试。")
                }
            }
        }
    }

    private fun info(message: String) = showDialog(
        AlertDialog.Builder(activity).setTitle("应用更新").setMessage(message)
            .setPositiveButton("知道了", null).create()
    )

    private fun confirm(title: String, message: String, label: String, action: () -> Unit) = showDialog(
        AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setNegativeButton("取消", null).setPositiveButton(label) { _, _ -> action() }.create()
    )

    private fun showDialog(next: AlertDialog) {
        if (destroyed || activity.isFinishing || activity.isDestroyed) return
        dialog?.dismiss()
        dialog = next
        next.show()
        (next.getButton(AlertDialog.BUTTON_POSITIVE) ?: next.getButton(AlertDialog.BUTTON_NEGATIVE))?.requestFocus()
    }

    fun destroy() {
        destroyed = true
        cancelBackgroundCheck()
        active?.cancel()
        active = null
        dialog?.dismiss()
        worker.shutdownNow()
    }

    @Suppress("DEPRECATION")
    private fun signingFlag() =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun PackageInfo.updateIdentity(): UpdateApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
        val signers = signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).updateHex()
        }.toSet()
        val code = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
        return UpdateApkIdentity(packageName, code, versionName.orEmpty(), signers)
    }

    private companion object { const val PERMISSION_REQUEST = 7116 }
}
