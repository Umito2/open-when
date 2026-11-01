package com.openwhen.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasePolicyTest {
    private val checksum = "a".repeat(64)
    private fun release(version: String = "1.3.0") = ReleaseInfo(
        version,
        "https://github.com/Umito2/open-when/releases/download/v$version/OpenWhen-$version.apk",
        checksum, 4_274_747,
        "https://github.com/Umito2/open-when/releases/tag/v$version",
    )
    private fun json(version: String = "1.3.0", name: String = "OpenWhen-$version.apk", digest: String = "sha256:$checksum") = """
        {"draft":false,"prerelease":false,"tag_name":"v$version",
         "html_url":"https://github.com/Umito2/open-when/releases/tag/v$version",
         "assets":[{"name":"$name","state":"uploaded","size":4274747,"digest":"$digest",
         "browser_download_url":"https://github.com/Umito2/open-when/releases/download/v$version/OpenWhen-$version.apk"}]}
    """.trimIndent()

    @Test fun `versions are compared numerically and equal or older builds never prompt`() {
        assertTrue(ReleasePolicy.isNewer("1.10.0", "1.9.9"))
        assertTrue(ReleasePolicy.isNewer("2.0.0", "1.99.99"))
        assertFalse(ReleasePolicy.isNewer("1.2.0", "1.2.0"))
        assertFalse(ReleasePolicy.isNewer("1.2.9", "1.3.0"))
        assertNull(ReleasePolicy.findUpdate(json("1.2.0"), "1.3.0"))
    }

    @Test fun `malformed and prerelease versions cannot become update candidates`() {
        listOf("1.3", "v1.3.0", "1.3.0-beta", "1.3.0-demo", "01.3.0", "9999999999.0.0", "1.3.0/evil", "1.3.0\n").forEach {
            assertFalse(it, ReleasePolicy.isNewer(it, "1.2.0"))
        }
        assertNull(ReleasePolicy.findUpdate(json().replace("\"prerelease\":false", "\"prerelease\":true"), "1.2.0"))
        assertNull(ReleasePolicy.findUpdate(json().replace("\"draft\":false", "\"draft\":true"), "1.2.0"))
    }

    @Test fun `published official release selects the exact APK and digest`() {
        val update = ReleasePolicy.findUpdate(json(), "1.2.0")
        assertNotNull(update)
        assertEquals(release(), update)
        assertTrue(ReleasePolicy.isTrusted(update!!))
    }

    @Test fun `asset names must match the stable version and be fully uploaded`() {
        assertNull(ReleasePolicy.findUpdate(json(name = "OpenWhen-1.3.0-demo.apk"), "1.2.0"))
        assertNull(ReleasePolicy.findUpdate(json(name = "OpenWhen-1.4.0.apk"), "1.2.0"))
        assertNull(ReleasePolicy.findUpdate(json().replace("\"uploaded\"", "\"new\""), "1.2.0"))
    }

    @Test fun `missing and malformed checksums are rejected before downloading`() {
        listOf("", "md5:$checksum", "sha256:abc", "sha256:${"g".repeat(64)}").forEach {
            assertNull(it, ReleasePolicy.findUpdate(json(digest = it), "1.2.0"))
        }
        assertFalse(ReleasePolicy.isTrusted(release().copy(sha256 = "")))
    }

    @Test fun `untrusted repository paths and external download URLs are rejected`() {
        listOf(
            "https://evil.example/OpenWhen-1.3.0.apk",
            "https://github.com/another/open-when/releases/download/v1.3.0/OpenWhen-1.3.0.apk",
            release().downloadUrl + "?redirect=evil",
            release().downloadUrl.replace("https:", "http:"),
        ).forEach { assertFalse(it, ReleasePolicy.isTrusted(release().copy(downloadUrl = it))) }
        assertNull(ReleasePolicy.findUpdate(json().replace("Umito2", "attacker"), "1.2.0"))
    }

    @Test fun `redirects permit only GitHub HTTPS asset servers without credentials or fragments`() {
        assertTrue(ReleasePolicy.isAllowedAssetUrl(release().downloadUrl))
        assertTrue(ReleasePolicy.isAllowedAssetUrl("https://release-assets.githubusercontent.com/github-production-release-asset/a?token=signature"))
        listOf(
            "http://release-assets.githubusercontent.com/file",
            "https://release-assets.githubusercontent.com.evil.example/file",
            "https://user:pass@release-assets.githubusercontent.com/file",
            "https://release-assets.githubusercontent.com:444/file",
            "https://release-assets.githubusercontent.com/file#fragment",
            "https://github.com/attacker/open-when/releases/download/v1.3.0/app.apk",
            "file:///private/app.apk",
        ).forEach { assertFalse(it, ReleasePolicy.isAllowedAssetUrl(it)) }
    }

    @Test fun `zero oversized and nonintegral asset lengths cannot consume unbounded storage`() {
        listOf(0L, -1L, ReleasePolicy.MAX_APK_BYTES + 1).forEach {
            assertFalse(ReleasePolicy.isTrusted(release().copy(sizeBytes = it)))
        }
        assertTrue(ReleasePolicy.isTrusted(release().copy(sizeBytes = ReleasePolicy.MAX_APK_BYTES)))
        assertNull(ReleasePolicy.findUpdate(json().replace("4274747", "4274747.5"), "1.2.0"))
    }
}
