// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.BackupData
import com.hongguotv.core.LibraryBackup
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption

data class LocalFeatureBackup(val data: BackupData, val tools: String, val savedAt: Long,val sources:String?=null,val appearance:String?=null)

/** Two validated private copies. Portable phone export is provided by TvTools, not broad storage access. */
class LocalBackupStorage(private val directory: File, private val external: ExternalBackupStore? = null) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "local-library-backups"), ExternalBackupStore(context.applicationContext))
    @Volatile private var externalFailure = false
    @Synchronized fun save(library: String, tools: String, savedAt: Long = System.currentTimeMillis(),sources:String?=null,appearance:String?=null) {
        // Re-encode validated library data so unknown fields can never be smuggled into local files.
        val data = LibraryBackup.decode(library)
        require(tools.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "工具备份过大" }
        val toolData = JSONObject(tools)
        require(toolData.optInt("version") == 1) { "工具备份版本不支持" }
        val safeTools = JSONObject()
        listOf("version", "skips", "groups", "assignments", "seekStep", "holdSpeed", "theme", "keys", "nextSeason","autoSkipIncompatible").forEach { key ->
            if (toolData.has(key)) safeTools.put(key, toolData.get(key))
        }
        val raw = PortableBackupCodec.encode(LibraryBackup.encode(data),safeTools.toString(),savedAt.coerceAtLeast(0),sources,appearance)
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        require(directory.isDirectory || directory.mkdirs()) { "无法创建备份目录" }
        val current = File(directory, "current.json")
        val validCurrent = read(current)?.takeIf { decode(it) != null }
        // A damaged latest file must not replace the known-good recovery copy.
        if (validCurrent != null) atomicWrite(File(directory, "previous.json"), validCurrent)
        atomicWrite(current, raw)
        // A removable drive/provider error cannot turn an already durable private save into failure.
        external?.takeIf { it.configured }?.let { store ->
            externalFailure = runCatching { store.mirror(raw).getOrThrow() }.isFailure
        }
    }
    @Synchronized fun load(): LocalFeatureBackup? =
        sequenceOf("current.json", "previous.json").mapNotNull { name -> read(File(directory, name))?.let(::decode) }.firstOrNull()
            ?: runCatching { external?.takeIf { it.configured }?.loadLatest() }.getOrNull()
    fun externalStatus(): String = (if (externalFailure && external?.configured == true) "本机已保存，外部复制失败，请检查目录授权或 USB 连接。\n" else "") + external?.status().orEmpty()
    private fun read(file: File): String? = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return null
        file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                if (output.size() + count > MAX_BYTES) return null
                output.write(buffer, 0, count)
            }
            String(output.toByteArray(), Charsets.UTF_8)
        }
    }.getOrNull()
    private fun decode(raw: String): LocalFeatureBackup? = runCatching {
        PortableBackupCodec.decode(raw)
    }.getOrNull()
    private fun atomicWrite(target: File, value: String) {
        val temporary = File(directory, "${target.name}.pending")
        FileOutputStream(temporary).use { output -> output.write(value.toByteArray(Charsets.UTF_8)); output.fd.sync() }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    private companion object { const val MAX_BYTES = PortableBackupCodec.MAX_BYTES }
}
