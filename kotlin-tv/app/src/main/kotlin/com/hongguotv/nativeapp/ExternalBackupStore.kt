// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Persisted SAF permission grants access only to the directory the user selects. */
class ExternalBackupStore(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val prefs = app.getSharedPreferences("native-external-backup-v1", Context.MODE_PRIVATE)
    private fun tree(): Uri? = prefs.getString("tree", null)?.let(Uri::parse)
    val configured: Boolean get() = tree() != null

    fun remember(uri: Uri) {
        val segments = uri.pathSegments
        require(uri.scheme == "content" && segments.size >= 2 && segments[0] == "tree") { "请选择系统目录" }
        prefs.edit().putString("tree", uri.toString()).putString("status", "外部目录已选择，等待备份").apply()
    }

    fun status(): String = if (!configured) "未选择外部目录；手机二维码也可导出备份"
        else prefs.getString("status", "外部目录已选择").orEmpty()

    private fun permitted(uri: Uri) = resolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission && it.isWritePermission
    }

    private fun document(name: String, create: Boolean): Uri? {
        val tree = tree() ?: return null
        check(permitted(tree)) { "外部目录授权已失效，请重新选择目录" }
        val parent = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == name)
                return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
        }
        return if (create) DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parent), "application/json", name) else null
    }

    private fun read(name: String): String? {
        val uri = document(name, false) ?: return null
        return resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= PortableBackupCodec.MAX_BYTES) { "外部备份文件过大" }
                out.write(buffer, 0, count)
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun write(name: String, raw: String) {
        val uri = document(name, true) ?: error("无法创建外部备份文件")
        val bytes = raw.toByteArray(Charsets.UTF_8)
        resolver.openOutputStream(uri, "wt")?.use { stream -> stream.write(bytes); stream.flush() }
            ?: error("外部备份目录不可写")
        check(read(name)?.let { PortableBackupCodec.sanitize(it) } == raw) { "外部备份回读未通过" }
    }

    @Synchronized fun mirror(raw: String): Result<Unit> = runCatching {
        if (!configured) return@runCatching
        val safe = PortableBackupCodec.sanitize(raw)
        // Keep the previous validated file before replacing current. A partial current write can recover.
        runCatching { read(CURRENT)?.let(PortableBackupCodec::sanitize) }.getOrNull()?.let { previous -> write(PREVIOUS, previous) }
        write(CURRENT, safe)
        prefs.edit().putString("status", "外部目录备份已同步；重装后重新选择此目录可恢复").apply()
    }.onFailure {
        prefs.edit().putString("status", "外部目录本次未写入；本机有效副本保留，请重新授权或稍后重试").apply()
    }

    @Synchronized fun loadLatest(): LocalFeatureBackup? {
        if (!configured) return null
        return sequenceOf(CURRENT, PREVIOUS).mapNotNull { name ->
            runCatching { read(name)?.let(PortableBackupCodec::decode) }.getOrNull()
        }.firstOrNull()
    }

    private companion object {
        const val CURRENT = "hongguotv-backup-current.json"
        const val PREVIOUS = "hongguotv-backup-previous.json"
    }
}
