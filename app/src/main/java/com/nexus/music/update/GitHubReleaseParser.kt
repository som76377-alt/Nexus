package com.nexus.music.update

import java.io.IOException
import java.net.URI
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

/**
 * Strictly parses the public GitHub release manifest before the APK can be downloaded.
 * Kept independent of Android Context so release metadata rules can be covered by JVM tests.
 */
internal fun parseLatestReleasePayload(
    payload: String,
    installedVersion: String,
    installedVersionCode: Long,
    repositoryOwner: String,
    repositoryName: String
): UpdateCheckResult {
    val release = try {
        JSONObject(payload)
    } catch (error: JSONException) {
        throw IOException("GitHub returned invalid release metadata.", error)
    }

    if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
        throw IOException("The latest Nexus release is not a stable published release.")
    }

    val tag = release.optString("tag_name").takeIf(String::isNotBlank)
        ?: throw IOException("The latest release has no version tag.")
    if (!tag.startsWith("v")) {
        throw IOException("Release tag '$tag' must use canonical vMAJOR.MINOR.PATCH format.")
    }
    val versionName = AppVersioning.normalize(tag)
        ?: throw IOException("Release tag '$tag' must use canonical vMAJOR.MINOR.PATCH format.")
    val versionCode = AppVersioning.versionCodeFromTag(tag)
        ?: throw IOException("Release tag '$tag' has an invalid version number.")

    if (versionCode < installedVersionCode) {
        // A signed validation or preview build can have a version that is not yet
        // published. Do not falsely label it "up to date" and do not offer a downgrade.
        return UpdateCheckResult.InstalledBuildAhead(installedVersion, versionName)
    }
    if (versionCode.toLong() == installedVersionCode) {
        return UpdateCheckResult.UpToDate(installedVersion)
    }

    val assets = release.optJSONArray("assets")
        ?: throw IOException("The latest release has no downloadable assets.")
    val matchingAssets = (0 until assets.length()).mapNotNull { index ->
        assets.optJSONObject(index)?.takeIf {
            it.optString("name").equals(APK_ASSET_NAME, ignoreCase = true)
        }
    }
    if (matchingAssets.isEmpty()) {
        throw IOException("The latest release does not contain $APK_ASSET_NAME.")
    }
    if (matchingAssets.size != 1) {
        throw IOException("The latest release contains more than one $APK_ASSET_NAME asset.")
    }

    val asset = matchingAssets.single()
    if (!asset.optString("state").equals("uploaded", ignoreCase = true)) {
        throw IOException("The Nexus release APK has not finished uploading.")
    }

    val apkUrl = asset.optString("browser_download_url")
    if (!isTrustedReleaseUrl(apkUrl, tag, repositoryOwner, repositoryName)) {
        throw IOException("The release APK link is not a trusted HTTPS Nexus GitHub release URL.")
    }

    val rawDigest = asset.optString("digest")
    val digestPrefix = rawDigest.substringBefore(':', missingDelimiterValue = "")
    val digest = rawDigest.substringAfter(':', missingDelimiterValue = "").lowercase(Locale.US)
    if (!digestPrefix.equals("sha256", ignoreCase = true) || !SHA256_PATTERN.matches(digest)) {
        throw IOException("The release APK is missing GitHub's SHA-256 asset digest.")
    }

    val assetSize = asset.optLong("size", -1L)
    if (assetSize <= 0L || assetSize > MAX_APK_BYTES) {
        throw IOException("The release APK has an invalid or excessive file size.")
    }

    val contentType = asset.optString("content_type")
    if (contentType.isNotBlank() &&
        !contentType.equals("application/vnd.android.package-archive", ignoreCase = true) &&
        !contentType.equals("application/octet-stream", ignoreCase = true)
    ) {
        throw IOException("The Nexus release asset is not an APK file.")
    }

    return UpdateCheckResult.Available(
        NexusUpdate(
            versionName = versionName,
            versionCode = versionCode,
            tagName = tag,
            releaseNotes = release.optString("body").take(MAX_RELEASE_NOTES_CHARS),
            apkUrl = apkUrl,
            sha256 = digest
        )
    )
}

/** Requires the exact repository, version tag, and APK asset path—not just a GitHub prefix. */
internal fun isTrustedReleaseUrl(
    value: String,
    tag: String,
    repositoryOwner: String,
    repositoryName: String
): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val expectedPath =
        "/$repositoryOwner/$repositoryName/releases/download/$tag/$APK_ASSET_NAME"
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("github.com", ignoreCase = true) &&
        uri.port == -1 &&
        uri.userInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null &&
        uri.rawPath.equals(expectedPath, ignoreCase = true)
}

private const val APK_ASSET_NAME = "nexus-release.apk"
private const val MAX_APK_BYTES = 150L * 1024L * 1024L
private const val MAX_RELEASE_NOTES_CHARS = 2_000
private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
