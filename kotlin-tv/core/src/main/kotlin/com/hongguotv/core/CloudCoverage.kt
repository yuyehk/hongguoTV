// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

/** Server list flags are diagnostic only until a public next-request contract is verified. */
data class CloudCoverage(
    val requestLimit: Int?,
    val serverHasMore: Boolean?,
    val message: String,
) {
    val complete: Boolean get() = false
    val paginationSupported: Boolean get() = false
}

data class CloudListPage<T>(val items: List<T>, val coverage: CloudCoverage)

internal fun cloudCoverage(raw: JSONObject, label: String, limit: Int? = null): CloudCoverage {
    val objects = listOfNotNull(raw, raw.optJSONObject("data"))
    val flags = objects.flatMap { obj -> listOf("has_more", "hasMore").mapNotNull { key ->
        when (val value = obj.opt(key)) {
            is Boolean -> value
            is Number -> when (value.toString()) { "0" -> false; "1" -> true; else -> null }
            else -> null
        }
    } }
    val more = flags.distinct().singleOrNull()
    val scope = if (limit == null) "本次读取" else if (label == "观看历史") "最近${limit}部" else "每类首批${limit}部"
    return CloudCoverage(limit, more, "$label：$scope，" +
        (if (more == true) "服务器仍有后续记录；" else "") +
        "分页协议未验证，不能保证覆盖全部账号记录")
}
