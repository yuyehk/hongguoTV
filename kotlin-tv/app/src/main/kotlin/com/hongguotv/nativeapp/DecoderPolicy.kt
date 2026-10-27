// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

internal enum class DecoderMode(val label: String) {
    AUTO("自动"), HARDWARE("硬件优先"), SOFTWARE("软件优先")
}

/** Preference changes ranking only: secure/tunneled queries and fallback candidates stay intact. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object DecoderPolicy {
    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("native-decoder-choice-v1", Context.MODE_PRIVATE)
    private fun mode(context: Context) = runCatching {
        DecoderMode.valueOf(preferences(context).getString("mode", DecoderMode.AUTO.name)!!)
    }.getOrDefault(DecoderMode.AUTO)

    fun modeLabel(context: Context) = mode(context).label

    fun <T> order(candidates: List<T>, mode: DecoderMode, software: (T) -> Boolean): List<T> = when (mode) {
        DecoderMode.AUTO -> candidates
        DecoderMode.HARDWARE -> candidates.sortedBy { if (software(it)) 1 else 0 }
        DecoderMode.SOFTWARE -> candidates.sortedBy { if (software(it)) 0 else 1 }
    }

    fun selector(context: Context): MediaCodecSelector {
        val selected = mode(context)
        return MediaCodecSelector { mime, secure, tunneling ->
            order(MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling), selected) { it.softwareOnly }
        }
    }

    fun showChooser(activity: Activity, onChanged: () -> Unit) {
        val selected = mode(activity)
        AlertDialog.Builder(activity)
            .setTitle("视频解码器 · 软件解码可能更耗资源")
            .setSingleChoiceItems(DecoderMode.entries.map { it.label }.toTypedArray(), selected.ordinal) { dialog, index ->
                val chosen = DecoderMode.entries[index]
                dialog.dismiss()
                if (chosen != selected) {
                    preferences(activity).edit().putString("mode", chosen.name).apply()
                    CodecCapabilities.resetLearned()
                    onChanged()
                }
            }
            .setNegativeButton("取消", null).show()
    }
}
