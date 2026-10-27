// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import java.net.URI
import java.security.MessageDigest

internal object SourceMediaPolicy {
    fun resumePosition(positionMs: Long, ended: Boolean): Long = if (ended) 0 else positionMs.coerceAtLeast(0)
    fun valid(value: String): Boolean = value.length in 1..8192 && value.none { it.isISOControl() } && runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    fun key(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal object SourcePlaybackPolicy {
    fun canAutoSkip(playRequested:Boolean,foreground:Boolean,sleepStopped:Boolean,errorCode:Int):Boolean =
        playRequested && foreground && !sleepStopped && errorCode in setOf(4001,4003,4004,4005)
    fun target(index: Int, count: Int, direction: Int, foreground: Boolean, sleepStopped: Boolean): Int? =
        if (!foreground || sleepStopped || direction !in listOf(-1, 1)) null else (index + direction).takeIf { it in 0 until count }
    fun progressKey(source: String, video: String, line: String, episode: String): String =
        SourceMediaPolicy.key(listOf(source, video, line, episode).joinToString("") { "${it.length}:$it" })
    fun introPosition(requested: Long, duration: Long): Long = requested.coerceIn(0, (duration - 1000).coerceAtLeast(0))
    fun outroStart(requested: Long, duration: Long, intro: Long): Long = duration.coerceAtLeast(0) -
        requested.coerceIn(0, (duration - introPosition(intro, duration) - 500).coerceAtLeast(0))
}

data class SourceMeasurement(val elapsedMs: Long, val success: Boolean)
internal object SourcePreference {
    fun order(ids: List<String>, manual: String?, measurements: Map<String, SourceMeasurement>): List<String> =
        ids.distinct().sortedWith(compareBy<String> { if (it == manual) 0 else 1 }
            .thenBy { when (measurements[it]?.success) { true -> 0; null -> 1; false -> 2 } }
            .thenBy { measurements[it]?.elapsedMs ?: Long.MAX_VALUE })
}

internal class SourceSkipBudget {
    private var skipped = 0
    fun allow(enabled: Boolean, codec: Boolean): Boolean {
        if (!enabled || !codec || skipped >= 5) return false
        skipped++; return true
    }
    fun rendered() { skipped = 0 }
}
