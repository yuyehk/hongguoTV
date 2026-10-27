package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class AndroidTvChannelsTest {
    @Test fun validatesOnlyOurNumericSeriesDeepLinks() {
        assertEquals("123456", AndroidTvChannels.seriesId("hongguotv://series/123456"))
        assertNull(AndroidTvChannels.seriesId("hongguotv://series/../123"))
        assertNull(AndroidTvChannels.seriesId("https://evil.test/series/123"))
        assertNull(AndroidTvChannels.seriesId("hongguotv://series/abc"))
        assertNull(AndroidTvChannels.seriesId("hongguotv://series/123?account=secret"))
    }
}
