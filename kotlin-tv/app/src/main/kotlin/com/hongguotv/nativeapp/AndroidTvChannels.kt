// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.media.tv.TvContract
import com.hongguotv.core.Series

/** Explicit opt-in desktop channel. ROMs without TV Provider return an actionable unsupported result. */
internal object AndroidTvChannels {
    private const val CHANNEL_KEY = "hongguotv-resume-v1"
    private val link = Regex("hongguotv://series/([0-9]{1,30})")
    fun seriesId(value: String?): String? = value?.let { link.matchEntire(it)?.groupValues?.get(1) }

    data class Published(val channelId: Long, val programs: Int)

    fun publish(context: Context, series: List<Series>): Result<Published> = runCatching {
        check(Build.VERSION.SDK_INT >= 26) { "系统不支持电视桌面频道" }
        check(context.packageManager.resolveContentProvider("android.media.tv", 0) != null) {
            "这台设备的桌面未提供 Android TV 频道接口，可继续从应用内续播"
        }
        val resolver = context.contentResolver
        val channels = TvContract.Channels.CONTENT_URI
        var channelId: Long? = null
        resolver.query(channels, arrayOf("_id", "internal_provider_id", "package_name"), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == CHANNEL_KEY && cursor.getString(2) == context.packageName) {
                    channelId = cursor.getLong(0); break
                }
            }
        }
        if (channelId == null) {
            val values = ContentValues().apply {
                put("type", "TYPE_PREVIEW")
                put("display_name", "红果TV · 接着看")
                put("internal_provider_id", CHANNEL_KEY)
                put("app_link_intent_uri", Intent(context, MainActivity::class.java).toUri(Intent.URI_INTENT_SCHEME))
            }
            channelId = resolver.insert(channels, values)?.let(ContentUris::parseId)
                ?: error("电视桌面拒绝创建频道")
        }
        val id = channelId!!
        // The provider limits this query to the caller's programs. Only replace this channel.
        val programs = Uri.parse("content://android.media.tv/preview_program")
        resolver.query(programs, arrayOf("_id", "channel_id"), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getLong(1) == id)
                resolver.delete(ContentUris.withAppendedId(programs, cursor.getLong(0)), null, null)
        }
        var count = 0
        series.distinctBy { it.id }.filter { it.id.matches(Regex("[0-9]{1,30}")) }.take(12).forEach { item ->
            val values = ContentValues().apply {
                put("channel_id", id)
                put("type", 0) // TYPE_TV_SERIES
                put("title", item.title)
                put("short_description", item.description.take(300))
                put("internal_provider_id", item.id)
                put("intent_uri", "hongguotv://series/${item.id}")
                put("weight", 100 - count)
                if (item.cover.startsWith("https://") || item.cover.startsWith("http://")) put("poster_art_uri", item.cover)
            }
            if (resolver.insert(programs, values) != null) count++
        }
        runCatching {
            val drawable = context.getDrawable(com.hongguotv.nativeapp.R.drawable.app_icon)!!
            val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 160, 160); drawable.draw(Canvas(bitmap))
            resolver.openOutputStream(TvContract.buildChannelLogoUri(id))?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        Published(id, count)
    }

    fun requestVisible(context: Context, channelId: Long) {
        if (Build.VERSION.SDK_INT >= 26) TvContract.requestChannelBrowsable(context, channelId)
    }
}
