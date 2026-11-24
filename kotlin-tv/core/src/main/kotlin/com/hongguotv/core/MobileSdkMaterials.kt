// SPDX-License-Identifier: GPL-3.0-only
// Fixed public protocol materials from the original 74532 application, verified
// through jackotom/hongguo-drama ea5a852f65acdc4c5b11265ca13b03d115096dc7
// phone_sdk_materials_v5.py. These are not device, account or session credentials.
package com.hongguotv.core

/** No runtime SDK download. Existing MobileV5Constants hold the identical frame,
 * signing key and all 1024 substitution bytes; only exchange materials are added. */
internal object MobileSdkMaterials {
    // sdk-app-config.encoded SHA256:
    // 584a01183f1e6783326bff49d4f1319f33fcce678afb93ca0dc8b60a56b65855
    val exchangeKey: ByteArray get() = hex("6fda42c82dc6084103cb078972edb06fa33ceb7a623b8a9d045c773a8602486c")
    // libmetasec_ml.so SHA256:
    // c6831ab265a0cb76dacfa3b7041e9ea0f706490106a74e12f39058671cc14fe3
    val exchangeInnerKey: ByteArray get() = hex("782399bdfacedead3230313030343034")
    const val unavailable = "!notset!"
}
