// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import java.io.File

/** Exact cache whitelist. Records, private files, source configuration and wallpaper are outside it. */
class SafeCacheManager(private val cacheRoot:File,private val clearPreview:()->Unit={}) {
    data class Result(val removedBytes:Long,val failures:Int)
    private fun files():List<File> {
        val root=cacheRoot.canonicalFile
        val directory=File(root,"artwork-v1").canonicalFile
        if(directory.parentFile!=root)return emptyList()
        return directory.listFiles().orEmpty().filter {it.isFile && it.canonicalFile.parentFile==directory}
    }
    fun bytes():Long=files().sumOf {it.length().coerceAtLeast(0)}
    fun clear():Result {
        var removed=0L;var failures=0
        files().forEach {file->val size=file.length();if(file.delete())removed+=size.coerceAtLeast(0) else failures++}
        clearPreview()
        return Result(removed,failures)
    }
}
