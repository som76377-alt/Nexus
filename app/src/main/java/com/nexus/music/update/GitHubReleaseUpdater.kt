package com.nexus.music.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.nexus.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.json.JSONObject

data class NexusUpdate(
    val versionName: String,
    val versionCode: Int,
    val tagName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val sha256: String
)

sealed interface UpdateCheckResult {
    data class UpToDate(val installedVersion: String) : UpdateCheckResult
    data class Available(val update: NexusUpdate) : UpdateCheckResult
}

/**
 * Reads published public GitHub Releases only. No GitHub token is embedded in the APK.
 * The public repository must have at least one published, non-draft, non-prerelease release.
 */
class GitHubReleaseUpdater(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
) {
    suspend fun checkForUpdate(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val apiUrl = "https://api.github.com/repos/" +
            "${BuildConfig.UPDATE_REPOSITORY_OWNER}/${BuildConfig.UPDATE_REPOSITORY_NAME}/releases/latest"
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .header("User-Agent", "Nexus-Android/${installedVersionName(context)}")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 404) {
                throw IOException(
                    "No published Nexus release was found. Publish a GitHub Release with a vMAJOR.MINOR.PATCH tag before checking for updates."
                )
            }
            if (!response.isSuccessful) {
                throw IOException("GitHub update check failed (HTTP ${response.code}).")
            }

            val payload = response.body?.string()
                ?: throw IOException("GitHub returned an empty release response.")
            val release = JSONObject(payload)
            val tag = release.optString("tag_name").takeIf(String::isNotBlank)
                ?: throw IOException("The latest release has no version tag.")
            val versionName = AppVersioning.normalize(tag)
                ?: throw IOException("Release tag '$tag' must use vMAJOR.MINOR.PATCH format.")
            val versionCode = AppVersioning.versionCodeFromTag(tag)
                ?: throw IOException("Release tag '$tag' has an invalid version number.")
            val installedVersion = installedVersionName(context)

            if (!AppVersioning.isNewer(versionName, installedVersion)) {
                return@withContext UpdateCheckResult.UpToDate(installedVersion)
            }

            val assets = release.optJSONArray("assets")
                ?: throw IOException("The latest release has no downloadable assets.")
            var apkAsset: JSONObject? = null
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                if (asset.optString("name").equals(APK_ASSET_NAME, ignoreCase = true)) {
                    apkAsset = asset
                    break
                }
            }
            val selectedAsset = apkAsset
                ?: throw IOException("The latest release does not contain $APK_ASSET_NAME.")

            val apkUrl = selectedAsset.optString("browser_download_url")
            val uri = runCatching { URI(apkUrl) }.getOrNull()
            if (uri?.scheme != "https" || uri.host != "github.com") {
                throw IOException("The release APK link is not a trusted HTTPS GitHub URL.")
            }

            val digest = selectedAsset.optString("digest")
                .removePrefix("sha256:")
                .lowercase(Locale.US)
            if (!SHA256_PATTERN.matches(digest)) {
                throw IOException("The release APK is missing GitHub's SHA-256 asset digest.")
            }

            UpdateCheckResult.Available(
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
    }

    suspend fun downloadAndVerify(context: Context, update: NexusUpdate): File =
        withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, "updates").apply {
                if (!exists() && !mkdirs()) throw IOException("Could not create the update cache.")
            }
            val apkFile = File(folder, "nexus-${update.versionName}.apk")
            val partialFile = File(folder, "nexus-${update.versionName}.apk.part")
            partialFile.delete()

            val request = Request.Builder()
                .url(update.apkUrl)
                .header("User-Agent", "Nexus-Android/${installedVersionName(context)}")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Could not download the update (HTTP ${response.code}).")
                    }
                    val body = response.body ?: throw IOException("The update download was empty.")
                    if (body.contentLength() > MAX_APK_BYTES) {
                        throw IOException("The update APK is larger than the allowed download size.")
                    }

                    val digest = MessageDigest.getInstance("SHA-256")
                    var totalBytes = 0L
                    body.byteStream().use { input ->
                        partialFile.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                totalBytes += count
                                if (totalBytes > MAX_APK_BYTES) {
                                    throw IOException("The update APK exceeded the allowed download size.")
                                }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }

                    if (totalBytes == 0L) throw IOException("The downloaded update was empty.")
                    val actualDigest = digest.digest().joinToString("") {
                        String.format(Locale.US, "%02x", it.toInt() and 0xff)
                    }
                    if (!actualDigest.equals(update.sha256, ignoreCase = true)) {
                        throw IOException("The update failed its SHA-256 integrity check.")
                    }
                }

                verifyApk(context, partialFile, update)
                if (apkFile.exists() && !apkFile.delete()) {
                    throw IOException("Could not replace the cached update APK.")
                }
                if (!partialFile.renameTo(apkFile)) {
                    partialFile.copyTo(apkFile, overwrite = true)
                    partialFile.delete()
                }
                apkFile
            } catch (error: Exception) {
                partialFile.delete()
                throw error
            }
        }

    private fun verifyApk(context: Context, apk: File, update: NexusUpdate) {
        @Suppress("DEPRECATION")
        val archiveInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        } ?: throw IOException("Android could not read the downloaded APK.")

        if (archiveInfo.packageName != context.packageName) {
            throw IOException("The downloaded APK is not a Nexus update.")
        }
        val downloadedVersionCode = if (Build.VERSION.SDK_INT >= 28) {
            archiveInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode.toLong()
        }
        if (downloadedVersionCode != update.versionCode.toLong()) {
            throw IOException("The APK version does not match the GitHub release tag.")
        }
        if (archiveInfo.versionName != update.versionName) {
            throw IOException("The APK version name does not match the GitHub release tag.")
        }
    }

    private fun installedVersionName(context: Context): String {
        @Suppress("DEPRECATION")
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return packageInfo.versionName ?: "0.1.0"
    }

    companion object {
        private const val APK_ASSET_NAME = "nexus-release.apk"
        private const val MAX_APK_BYTES = 150L * 1024L * 1024L
        private const val MAX_RELEASE_NOTES_CHARS = 2_000
        private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
    }
}
