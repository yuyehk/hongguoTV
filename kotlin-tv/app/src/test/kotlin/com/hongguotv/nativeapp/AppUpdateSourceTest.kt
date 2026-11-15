package com.hongguotv.nativeapp

import java.io.IOException
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateSourceTest {
    @get:Rule val folder = TemporaryFolder()
    private val name = "hongguotv-0.11.10-android14-transitions.apk"
    private val version = "0.11.10-android14-transitions"
    private val bytes = "test APK".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).updateHex()

    private fun source(checksum: String = hash, fileName: String = name) =
        AppUpdateSource(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.url.pathSegments.last()) {
                "SHA256SUMS" -> "$checksum  $fileName\n".toResponseBody()
                name -> bytes.toResponseBody()
                else -> error("Unexpected update request")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(body).build()
        }.build())

    @Test fun detectsNewStableReleaseUsingNumericVersionComparison() {
        assertEquals(version, source().check("0.11.9-android14-transitions")?.versionName)
        assertNull(source().check(version))
        assertNull(source().check("0.12.0-android14-transitions"))
        assertThrows(IOException::class.java) { source(fileName = "unrelated.apk").check("0.11.9") }
    }

    @Test fun downloadsOnlyAnApkMatchingThePublishedChecksum() {
        val source = source()
        val release = source.check("0.11.9")!!
        val file = source.download(release, folder.root) {}
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(file.name.endsWith(".apk"))

        val tampered = source(checksum = "0".repeat(64))
        assertThrows(IOException::class.java) { tampered.download(tampered.check("0.11.9")!!, folder.root) {} }
        assertEquals(listOf(file), folder.root.listFiles()!!.toList())
    }

    @Test fun rejectsWrongPackageVersionOrSignerBeforeInstall() {
        val installed = UpdateApkIdentity("com.hongguotv.nativeapp", 21L, "0.11.7-android14-transitions", setOf("original"))
        val candidate = installed.copy(versionCode = 24L, versionName = version)
        validateUpdateApk(installed, candidate, version)
        for (invalid in listOf(candidate.copy(packageName = "another.app"),
            candidate.copy(versionCode = 21L), candidate.copy(versionName = "0.11.8"),
            candidate.copy(signers = setOf("other")))) {
            assertThrows(IOException::class.java) { validateUpdateApk(installed, invalid, version) }
        }
    }
}
