package com.nexus.music.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.nexus.music.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
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
 * Downloading and release-feed requests use separate timeout profiles.
 */
class GitHubReleaseUpdater(
    private val apiClient: OkHttpClient = API_CLIENT,
    private val downloadClient: OkHttpClient = DOWNLOAD_CLIENT
) {
    suspend fun checkForUpdate(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val installedVersion = installedVersionName(context)
        val installedVersionCode = installedVersionCode(context)
        val apiUrl = "https://api.github.com/repos/" +
            "${BuildConfig.UPDATE_REPOSITORY_OWNER}/${BuildConfig.UPDATE_REPOSITORY_NAME}/releases/latest"
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .header("User-Agent", "Nexus-Android/$installedVersion")
            .get()
            .build()

        apiClient.newCall(request).execute().use { response ->
            if (response.code == 404) {
                throw IOException(
                    "No published Nexus release was found. Publish a stable GitHub Release before checking for updates."
                )
            }
            if (response.code == 403 || response.code == 429) {
                throw IOException("GitHub is temporarily limiting update checks. Please try again later.")
            }
            if (!response.isSuccessful) {
                throw IOException("GitHub update check failed (HTTP ${response.code}).")
            }

            val payload = response.body?.string()
                ?: throw IOException("GitHub returned an empty release response.")
            val release = JSONObject(payload)
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
                throw IOException("The latest Nexus release is not a stable published release.")
            }

            val tag = release.optString("tag_name").takeIf(String::isNotBlank)
                ?: throw IOException("The latest release has no version tag.")
            val versionName = AppVersioning.normalize(tag)
                ?: throw IOException("Release tag '$tag' must use canonical vMAJOR.MINOR.PATCH format.")
            val versionCode = AppVersioning.versionCodeFromTag(tag)
                ?: throw IOException("Release tag '$tag' has an invalid version number.")

            if (!AppVersioning.isNewer(versionCode, installedVersionCode)) {
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
            if (!isTrustedReleaseUrl(apkUrl)) {
                throw IOException("The release APK link is not a trusted HTTPS Nexus GitHub release URL.")
            }

            val digest = selectedAsset.optString("digest")
                .removePrefix("sha256:")
                .lowercase(Locale.US)
            if (!SHA256_PATTERN.matches(digest)) {
                throw IOException("The release APK is missing GitHub's SHA-256 asset digest.")
            }

            val assetSize = selectedAsset.optLong("size", -1L)
            if (assetSize == 0L || assetSize > MAX_APK_BYTES) {
                throw IOException("The release APK has an invalid or excessive file size.")
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

    suspend fun downloadAndVerify(
        context: Context,
        update: NexusUpdate,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File =
        withContext(Dispatchers.IO) {
            validateUpdateMetadata(update)

            val folder = File(context.cacheDir, "updates")
            if (!folder.exists() && !folder.mkdirs()) {
                throw IOException("Could not create the update cache.")
            }
            if (!folder.isDirectory) {
                throw IOException("The update cache path is not a directory.")
            }

            val apkFile = File(folder, "nexus-${update.versionName}.apk")
            val partialFile = File(folder, "nexus-${update.versionName}.apk.part")
            if (partialFile.exists() && !partialFile.delete()) {
                throw IOException("Could not clear an incomplete previous download.")
            }

            val request = Request.Builder()
                .url(update.apkUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "Nexus-Android/${installedVersionName(context)}")
                .get()
                .build()

            try {
                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val message = when (response.code) {
                            403, 429 -> "GitHub is temporarily limiting downloads. Please try again later."
                            404 -> "The APK asset is no longer available in this GitHub release."
                            else -> "Could not download the update (HTTP ${response.code})."
                        }
                        throw IOException(message)
                    }

                    if (!isTrustedDownloadHost(response.request.url.host) || !response.request.url.isHttps) {
                        throw IOException("GitHub redirected the APK download to an untrusted location.")
                    }

                    val body = response.body ?: throw IOException("The update download was empty.")
                    val expectedLength = body.contentLength()
                    if (expectedLength > MAX_APK_BYTES) {
                        throw IOException("The update APK is larger than the allowed download size.")
                    }

                    onProgress(0L, expectedLength)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var totalBytes = 0L
                    body.byteStream().use { input ->
                        partialFile.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                totalBytes += count
                                if (totalBytes > MAX_APK_BYTES) {
                                    throw IOException("The update APK exceeded the allowed download size.")
                                }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                onProgress(totalBytes, expectedLength)
                            }
                            output.flush()
                        }
                    }

                    if (totalBytes == 0L) throw IOException("The downloaded update was empty.")
                    if (expectedLength >= 0L && totalBytes != expectedLength) {
                        throw IOException("The update download ended early. Check your connection and try again.")
                    }

                    val actualDigest = digest.digest().toHexString()
                    if (!actualDigest.equals(update.sha256, ignoreCase = true)) {
                        throw IOException("The update failed its SHA-256 integrity check. The file was discarded.")
                    }
                }

                verifyApk(context, partialFile, update)

                if (apkFile.exists() && !apkFile.delete()) {
                    throw IOException("Could not replace the cached update APK.")
                }
                if (!partialFile.renameTo(apkFile)) {
                    partialFile.copyTo(apkFile, overwrite = true)
                    if (!partialFile.delete()) partialFile.deleteOnExit()
                }
                apkFile
            } catch (error: CancellationException) {
                partialFile.delete()
                throw error
            } catch (error: SocketTimeoutException) {
                partialFile.delete()
                throw IOException(
                    "The update download timed out. Check your connection and tap Download update to retry.",
                    error
                )
            } catch (error: Exception) {
                partialFile.delete()
                throw error
            }
        }

    /** Re-checks a previously downloaded APK before handing it to PackageInstaller. */
    fun findVerifiedCachedUpdate(context: Context, update: NexusUpdate): File? {
        val file = File(File(context.cacheDir, "updates"), "nexus-${update.versionName}.apk")
        if (!file.isFile || file.length() <= 0L) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (!digest.digest().toHexString().equals(update.sha256, ignoreCase = true)) {
            file.delete()
            return null
        }
        verifyApk(context, file, update)
        return file
    }

    private fun validateUpdateMetadata(update: NexusUpdate) {
        val tagVersion = AppVersioning.normalize(update.tagName)
        val expectedCode = AppVersioning.versionCodeFromTag(update.tagName)
        if (tagVersion != update.versionName || expectedCode != update.versionCode) {
            throw IOException("The update version information is inconsistent. Check for updates again.")
        }
        if (!SHA256_PATTERN.matches(update.sha256)) {
            throw IOException("The release is missing a valid SHA-256 checksum.")
        }
        if (!isTrustedReleaseUrl(update.apkUrl)) {
            throw IOException("The release APK link is not a trusted HTTPS Nexus GitHub release URL.")
        }
    }

    private fun verifyApk(context: Context, apk: File, update: NexusUpdate) {
        val archiveFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val archiveInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(archiveFlags.toLong())
            )
        } else {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, archiveFlags)
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

        val installedInfo = getInstalledPackageInfo(context, archiveFlags)
        if (!hasCompatibleSigningIdentity(installedInfo, archiveInfo)) {
            throw IncompatibleSigningCertificateException()
        }
    }

    private fun getInstalledPackageInfo(context: Context, flags: Int): PackageInfo {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun hasCompatibleSigningIdentity(installed: PackageInfo, downloaded: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val installedSigning = installed.signingInfo ?: return false
            val downloadedSigning = downloaded.signingInfo ?: return false

            if (installedSigning.hasMultipleSigners() || downloadedSigning.hasMultipleSigners()) {
                if (!installedSigning.hasMultipleSigners() || !downloadedSigning.hasMultipleSigners()) {
                    return false
                }
                return signerFingerprints(installedSigning.apkContentsSigners) ==
                    signerFingerprints(downloadedSigning.apkContentsSigners)
            }

            val installedCurrentSigners = signerFingerprints(installedSigning.apkContentsSigners)
            val downloadedHistory = signerFingerprints(downloadedSigning.signingCertificateHistory)
            return installedCurrentSigners.isNotEmpty() &&
                installedCurrentSigners.all { it in downloadedHistory }
        }

        return signerFingerprints(installed.signatures) == signerFingerprints(downloaded.signatures)
    }

    private fun signerFingerprints(signatures: Array<Signature>?): Set<String> =
        signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .toHexString()
        }.toSet()

    private fun installedVersionCode(context: Context): Long {
        @Suppress("DEPRECATION")
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
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
        return packageInfo.versionName
            ?: throw IOException("Android could not read the installed Nexus version.")
    }

    private fun isTrustedReleaseUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val expectedPathPrefix =
            "/${BuildConfig.UPDATE_REPOSITORY_OWNER}/${BuildConfig.UPDATE_REPOSITORY_NAME}/releases/download/"
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.port == -1 &&
            uri.userInfo == null &&
            uri.rawPath.startsWith(expectedPathPrefix)
    }

    private fun isTrustedDownloadHost(host: String): Boolean =
        host.equals("github.com", ignoreCase = true) ||
            host.lowercase(Locale.US).endsWith(".githubusercontent.com")

    companion object {
        private const val APK_ASSET_NAME = "nexus-release.apk"
        private const val MAX_APK_BYTES = 150L * 1024L * 1024L
        private const val MAX_RELEASE_NOTES_CHARS = 2_000
        private const val DOWNLOAD_BUFFER_BYTES = 32 * 1024
        private const val GITHUB_API_VERSION = "2026-03-10"
        private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")

        private val API_CLIENT = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private val DOWNLOAD_CLIENT = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

class IncompatibleSigningCertificateException : IOException(
    "This installed copy of Nexus was signed with a different key. Android cannot update it in place. " +
        "To move to the official release, back up anything important, uninstall this copy, then install the official APK. " +
        "Uninstalling the app may erase its local data."
)
