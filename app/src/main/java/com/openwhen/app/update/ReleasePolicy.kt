package com.openwhen.app.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/** Restricts this updater to the stable, signed Spark APKs from this repository. */
object ReleasePolicy {
    const val LATEST_URL = "https://api.github.com/repos/Umito2/open-when/releases/latest"
    const val MAX_APK_BYTES = 100L * 1024 * 1024
    private const val REPOSITORY_URL = "https://github.com/Umito2/open-when"
    private val versionPattern = Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")
    private val digestPattern = Regex("[a-fA-F0-9]{64}")

    fun isNewer(candidate: String, installed: String): Boolean {
        val next = versionParts(candidate) ?: return false
        val current = versionParts(installed) ?: return false
        for (index in next.indices) {
            if (next[index] != current[index]) return next[index] > current[index]
        }
        return false
    }

    fun findUpdate(json: String, installed: String): ReleaseInfo? {
        val root = JsonParser.parseString(json).asJsonObject
        if (root.get("draft")?.toString() != "false" || root.get("prerelease")?.toString() != "false") return null
        val tag = root.text("tag_name") ?: return null
        if (!tag.startsWith("v")) return null
        val version = tag.removePrefix("v")
        if (!isNewer(version, installed)) return null
        val page = "$REPOSITORY_URL/releases/tag/$tag"
        if (root.text("html_url") != page) return null
        val expectedName = "OpenWhen-$version.apk"
        val assets = root.getAsJsonArray("assets") ?: return null
        return assets.asSequence().filter { it.isJsonObject }.map { it.asJsonObject }.mapNotNull { asset ->
            if (asset.text("name") != expectedName || asset.text("state") != "uploaded") return@mapNotNull null
            val digest = asset.text("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                ?: return@mapNotNull null
            val size = asset.get("size")?.toString()?.takeIf { it.matches(Regex("[0-9]{1,10}")) }?.toLongOrNull()
                ?: return@mapNotNull null
            val download = asset.text("browser_download_url") ?: return@mapNotNull null
            ReleaseInfo(version, download, digest.lowercase(), size, page).takeIf(::isTrusted)
        }.singleOrNull()
    }

    fun isTrusted(release: ReleaseInfo): Boolean = versionParts(release.versionName) != null &&
        release.sizeBytes in 1..MAX_APK_BYTES && digestPattern.matches(release.sha256) &&
        release.releaseUrl == "$REPOSITORY_URL/releases/tag/v${release.versionName}" &&
        release.downloadUrl == "$REPOSITORY_URL/releases/download/v${release.versionName}/OpenWhen-${release.versionName}.apk"

    fun isAllowedAssetUrl(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null &&
            uri.port in setOf(-1, 443) &&
            (uri.host == "release-assets.githubusercontent.com" ||
                (uri.host == "github.com" && uri.rawPath.startsWith("/Umito2/open-when/releases/download/")))
    } catch (_: Exception) { false }

    private fun versionParts(version: String): List<Int>? =
        versionPattern.matchEntire(version)?.groupValues?.drop(1)?.map(String::toInt)

    private fun JsonObject.text(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
