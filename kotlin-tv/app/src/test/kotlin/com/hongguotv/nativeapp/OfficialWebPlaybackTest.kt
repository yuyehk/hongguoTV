// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.EmptyContentResponse
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialWebPlaybackTest {
    @Test fun onlyEmptyPlaybackResponsesSelectTheInPageFallback() {
        val series = "7707078673207151678"
        val episode = "7707465250475346969"
        assertEquals(
            "https://hongguoduanju.com/player/$series/$episode",
            officialPlaybackFallback(EmptyContentResponse(), series, episode),
        )
        assertNull(officialPlaybackFallback(SocketTimeoutException(), series, episode))
        assertNull(officialPlaybackFallback(EmptyContentResponse(), series, null))
    }

    @Test fun opensTheExactEpisodeOnTheOfficialWebsite() {
        assertEquals(
            "https://hongguoduanju.com/player/7707078673207151678/7707465250475346969",
            officialEpisodePage("7707078673207151678", "7707465250475346969"),
        )
        assertNull(officialEpisodePage("7707078673207151678", null))
        assertNull(officialEpisodePage("7707078673207151678", "1/2"))
        assertNull(officialEpisodePage("../", "7707465250475346969"))
    }

    @Test fun allowsSecureOfficialNavigationOnly() {
        assertTrue(isOfficialPlaybackNavigation("https://hongguoduanju.com/player/123/456"))
        assertTrue(isOfficialPlaybackNavigation("https://hongguoduanju.com:443/detail?series_id=123"))
        assertFalse(isOfficialPlaybackNavigation("http://hongguoduanju.com/player/123"))
        assertFalse(isOfficialPlaybackNavigation("https://hongguoduanju.com:8443/player/123"))
        assertFalse(isOfficialPlaybackNavigation("https://hongguoduanju.com.example.net/player/123"))
        assertFalse(isOfficialPlaybackNavigation("https://user@hongguoduanju.com/player/123"))
        assertFalse(isOfficialPlaybackNavigation("file:///tmp/player"))
        assertFalse(isOfficialPlaybackNavigation("not a URL"))
    }
}
