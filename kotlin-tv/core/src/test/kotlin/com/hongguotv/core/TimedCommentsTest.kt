// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TimedCommentsTest {
    @Test
    fun deterministicCommentSignature() {
        val h =
            CommentSignature()
                .headers("aid=8662&device_id=123", 1700000000123, ByteArray(11) { it.toByte() })
        assertEquals("840420e100007c44bd0bfe74163888622f1454edf858fca6234c", h["X-Gorgon"])
        assertEquals("AgMEBVbVr6bCGXEl3RbL1aAkpFGUXoV+4yYWolKDMC0DpDXW", h["X-Ladon"])
        assertEquals(
            "PMxPj+JImxTKEfQuAsVmWpqWW55ysbz50VgZsw7nAusOgw1l8frdM9uBXSga5sP42uqiFTi9IcF4fLQ1tKcCzvCOSimC41NEVEjNJLzfEjRxKwyqbtbQ2qLvZl/PhMRPjEMMqLnDX064CGeH7Rt9JbxGmLTNLw4v83XXXrMDozK17tDXIJ3nJPCCh70TOfOE//cWmfoxWrWZ68qMlErAsRCe",
            h["X-Argus"],
        )
    }

    @Test
    fun filtersPromptsOtherEpisodesDuplicatesAndInvalidTimes() {
        fun row(id: String, time: Long, status: Int = 1, episode: String = "234567") =
            JSONObject()
                .put(
                    "comment",
                    JSONObject()
                        .put("comment_id", id)
                        .put(
                            "common",
                            JSONObject()
                                .put("group_id", episode)
                                .put("status", status)
                                .put("content", JSONObject().put("text", "文本")),
                        )
                        .put("expand", JSONObject().put("offset_time", time)),
                )
        val data =
            JSONObject()
                .put("extra", JSONObject().put("next_query_danmaku_list_time", 30000))
                .put(
                    "data_list",
                    JSONArray(
                        listOf(
                            row("valid", 1000),
                            row("valid", 1000),
                            row("prompt", 0, 0),
                            row("other", 2000, episode = "345678"),
                            row("late", 30000),
                            row("negative", -1),
                        )
                    ),
                )
        val result = JSONObject().put("code", 0).put("data", data)
        val parsed = TimedComments.parse(result, "123456", "234567", 0, 90000)
        assertEquals(1, parsed.getJSONArray("items").length())
        assertEquals(30000, parsed.getInt("nextMS"))
        data.getJSONObject("extra").put("next_query_danmaku_list_time", 0)
        try {
            TimedComments.parse(result, "123456", "234567", 0, 90000)
            fail("non-advancing window accepted")
        } catch (_: IllegalArgumentException) {}
    }
}
