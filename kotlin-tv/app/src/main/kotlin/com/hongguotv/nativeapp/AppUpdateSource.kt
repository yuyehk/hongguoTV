// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.RequestScope
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

internal data class AppUpdate(val tag: String, val versionName: String, val fileName: String, val sha256: String)

internal class UpdateHttpException(val statusCode: Int) :
    IOException("更新服务器暂不可用（HTTP $statusCode）。")

internal data class UpdateApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val signers: Set<String>,
)

internal fun validateUpdateApk(installed: UpdateApkIdentity, candidate: UpdateApkIdentity, expectedVersion: String) {
    if (candidate.packageName != installed.packageName) throw IOException("安装包与红果 TV 的包名不一致。")
    if (candidate.versionCode <= installed.versionCode || candidate.versionName != expectedVersion) {
        throw IOException("安装包版本与发布信息不符，或不高于当前版本。")
    }
    if (installed.signers.isEmpty() || candidate.signers != installed.signers) {
        throw IOException("安装包签名与当前应用不一致。")
    }
}

internal fun ByteArray.updateHex() = joinToString("") { "%02x".format(it.toInt() and 255) }

internal class AppUpdateSource(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).build(),
) {
    fun check(currentVersion: String): AppUpdate? {
        val current = VERSION.matchAt(currentVersion, 0) ?: throw IOException("无法识别当前版本。")
        val update = fetch(LATEST_CHECKSUM) { body ->
            body.smallText().lineSequence().mapNotNull { CHECKSUM.matchEntire(it.trim()) }
                .mapNotNull { entry ->
                    val name = entry.groupValues[2]
                    val apk = APK_NAME.matchEntire(name) ?: return@mapNotNull null
                    AppUpdate("v${apk.groupValues[2]}", apk.groupValues[1], name, entry.groupValues[1].lowercase())
                }.toList().singleOrNull()
                ?: throw IOException("校验文件中没有唯一的 Android TV 安装包。")
        }
        val published = checkNotNull(VERSION.matchAt(update.versionName, 0))
        val comparison = (1..3).asSequence().map {
            published.groupValues[it].toInt().compareTo(current.groupValues[it].toInt())
        }.firstOrNull { it != 0 } ?: 0
        return update.takeIf { comparison > 0 }
    }

    fun download(update: AppUpdate, directory: File, progress: (Int) -> Unit): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建更新目录。")
        val staged = File.createTempFile("update-", ".part", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            fetch(asset(update.tag, update.fileName)) { body ->
                val advertised = body.contentLength()
                if (advertised == 0L || advertised > APK_LIMIT) throw IOException("安装包大小超出允许范围。")
                var total = 0L
                var shown = -1
                staged.outputStream().buffered().use { output ->
                    val input = body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > APK_LIMIT) throw IOException("安装包大小超出允许范围。")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        if (advertised > 0) {
                            val percent = (total * 100 / advertised).toInt().coerceAtMost(100)
                            if (shown != percent) { shown = percent; progress(percent) }
                        }
                    }
                }
                if (total == 0L || (advertised > 0 && total != advertised)) throw IOException("安装包下载不完整。")
            }
            if (digest.digest().updateHex() != update.sha256) throw IOException("安装包校验失败。")
            val target = File(directory, update.fileName)
            if (!staged.renameTo(target)) {
                if (target.exists() && !target.delete()) throw IOException("无法替换更新文件。")
                if (!staged.renameTo(target)) throw IOException("无法保存更新文件。")
            }
            return target
        } finally {
            staged.delete()
        }
    }

    private fun <T> fetch(start: HttpUrl, consume: (ResponseBody) -> T): T {
        var url = start
        repeat(5) {
            if (!url.isHttps || url.port != 443 || url.host !in HOSTS) throw IOException("更新下载地址不受信任。")
            val request = Request.Builder().url(url).header("User-Agent", "HongguoTV-Updater").build()
            val result: FetchResult<T> = RequestScope.execute(http.newCall(request)) { response ->
                if (response.isRedirect) {
                    val location = response.header("Location")?.let(url::resolve)
                        ?: throw IOException("更新下载地址无效。")
                    Redirect(location)
                } else {
                    if (!response.isSuccessful) throw UpdateHttpException(response.code)
                    Completed(consume(response.body ?: throw IOException("更新服务器返回空内容。")))
                }
            }
            when (result) {
                is Completed -> return result.value
                is Redirect -> url = result.url
            }
        }
        throw IOException("更新下载跳转次数过多。")
    }

    private sealed interface FetchResult<out T>
    private data class Completed<T>(val value: T) : FetchResult<T>
    private data class Redirect(val url: HttpUrl) : FetchResult<Nothing>

    private fun ResponseBody.smallText(): String {
        if (contentLength() > TEXT_LIMIT) throw IOException("更新信息过大。")
        val input = source()
        if (input.request(TEXT_LIMIT.toLong() + 1)) throw IOException("更新信息过大。")
        return input.readUtf8()
    }

    private fun asset(tag: String, name: String) =
        RELEASES.newBuilder().addPathSegment(tag).addPathSegment(name).build()

    private companion object {
        val LATEST_CHECKSUM = "https://github.com/yuyehk/hongguoTV/releases/latest/download/SHA256SUMS".toHttpUrl()
        val RELEASES = "https://github.com/yuyehk/hongguoTV/releases/download".toHttpUrl()
        val VERSION = Regex("""(\d{1,6})\.(\d{1,6})\.(\d{1,6})""")
        val APK_NAME = Regex("""hongguotv-((\d{1,6}\.\d{1,6}\.\d{1,6})-android14-(?:transitions|accounts))\.apk""")
        val CHECKSUM = Regex("""([0-9a-fA-F]{64})\s+\*?([^\s]+)""")
        val HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        const val TEXT_LIMIT = 256 * 1024
        const val APK_LIMIT = 64L * 1024 * 1024
    }
}
