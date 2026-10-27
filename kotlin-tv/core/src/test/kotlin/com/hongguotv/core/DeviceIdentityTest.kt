package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class DeviceIdentityTest {
    @Test fun generatedIdentitiesAreNumericAndDistinct() {
        val ids=(1..1000).map { DeviceIdentity.create() }
        assertTrue(ids.all(DeviceIdentity::isValid))
        assertEquals(1000,ids.toSet().size)
        assertFalse("1905892595378490" in ids)
    }
    @Test fun identityRejectsMalformedSavedValues() {
        listOf("", "0", "0123456789012345", "123456789012345a").forEach {
            assertFalse(DeviceIdentity.isValid(it))
            assertThrows(IllegalArgumentException::class.java) { DeviceIdentity.use(it) }
        }
    }
    @Test fun api23CompatibleBase64RoundTripsBinaryAndUrlSafeInput() {
        val data=ByteArray(256) { it.toByte() }
        assertArrayEquals(data,decode64(data.base64()))
        assertArrayEquals(data,decode64(data.base64().replace('+','-').replace('/','_')))
    }
}
