// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.security.SecureRandom

/** Random numeric vendor identity, independent of Android's hardware identifiers. */
object DeviceIdentity {
    fun create(): String {
        val random=SecureRandom()
        return buildString { append(1+random.nextInt(9)); repeat(15) { append(random.nextInt(10)) } }
    }
    fun isValid(value: String) = value.matches(Regex("[1-9][0-9]{15}"))
    fun use(value: String) {
        require(isValid(value))
        VendorConstants.device["device_id"]=value
    }
}
