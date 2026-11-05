package com.nexus.music.update

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubReleaseParserTest {
    private val digest = "a".repeat(64)
    private val owner = "som76377-alt"
    private val repository = "Nexus"

    @Test
    fun parsesAndValidatesACompleteNewRelease() {
        val result = parseLatestReleasePayload(
            payload = releasePayload(),
            installedVersion = "0.0.0-dev",
            installedVersionCode = 1L,
            repositoryOwner = owner,
            repositoryName = repository
        )

        assertTrue(result is UpdateCheckResult.Available)
        val update = (result as UpdateCheckResult.Available).update
        assertEquals("0.1.8", update.versionName)
        assertEquals(1_008, update.versionCode)
        assertEquals("v0.1.8", update.tagName)
        assertEquals(digest, update.sha256)
        assertEquals(
            "https://github.com/$owner/$repository/releases/download/v0.1.8/nexus-release.apk",
            update.apkUrl
        )
    }

    @Test
    fun sameOrOlderPublishedReleaseIsNotAnUpdate() {
        val result = parseLatestReleasePayload(
            payload = releasePayload(tag = "v0.1.7", assets = JSONArray()),
            installedVersion = "0.1.7",
            installedVersionCode = 1_007L,
            repositoryOwner = owner,
            repositoryName = repository
        )
        assertEquals(UpdateCheckResult.UpToDate("0.1.7"), result)
    }

    @Test
    fun labelsAnUnpublishedInstalledBuildAsAheadInsteadOfUpToDate() {
        val result = parseLatestReleasePayload(
            payload = releasePayload(tag = "v0.1.7", assets = JSONArray()),
            installedVersion = "0.1.8",
            installedVersionCode = 1_008L,
            repositoryOwner = owner,
            repositoryName = repository
        )
        assertEquals(
            UpdateCheckResult.InstalledBuildAhead("0.1.8", "0.1.7"),
            result
        )
    }

    @Test
    fun refusesDraftAndPrereleaseManifests() {
        assertThrows(IOException::class.java) {
            parseLatestReleasePayload(releasePayload(draft = true), "0.1.7", 1_007L, owner, repository)
        }
        assertThrows(IOException::class.java) {
            parseLatestReleasePayload(releasePayload(prerelease = true), "0.1.7", 1_007L, owner, repository)
        }
    }

    @Test
    fun refusesNonCanonicalReleaseTags() {
        listOf("latest", "0.1.8", "v0.1", "v0.1.8-beta", "v00.1.8").forEach { tag ->
            assertThrows(IOException::class.java) {
                parseLatestReleasePayload(releasePayload(tag = tag), "0.0.0-dev", 1L, owner, repository)
            }
        }
    }

    @Test
    fun refusesMissingOrDuplicateApkAssets() {
        assertThrows(IOException::class.java) {
            parseLatestReleasePayload(
                releasePayload(assets = JSONArray().put(asset(name = "notes.txt"))),
                "0.0.0-dev", 1L, owner, repository
            )
        }
        assertThrows(IOException::class.java) {
            parseLatestReleasePayload(
                releasePayload(assets = JSONArray().put(asset()).put(asset())),
                "0.0.0-dev", 1L, owner, repository
            )
        }
    }

    @Test
    fun refusesBadDigestSizeContentTypeAndUploadState() {
        val brokenAssets = listOf(
            asset(digestValue = "md5:$digest"),
            asset(size = 0L),
            asset(size = 150L * 1024L * 1024L + 1L),
            asset(contentType = "text/plain"),
            asset(state = "starter")
        )
        brokenAssets.forEach { invalidAsset ->
            assertThrows(IOException::class.java) {
                parseLatestReleasePayload(
                    releasePayload(assets = JSONArray().put(invalidAsset)),
                    "0.0.0-dev", 1L, owner, repository
                )
            }
        }
    }

    @Test
    fun refusesUrlsOutsideTheExactHttpsReleaseAssetPath() {
        val urls = listOf(
            "http://github.com/$owner/$repository/releases/download/v0.1.8/nexus-release.apk",
            "https://github.com.evil.test/$owner/$repository/releases/download/v0.1.8/nexus-release.apk",
            "https://github.com/other/repository/releases/download/v0.1.8/nexus-release.apk",
            "https://github.com/$owner/$repository/releases/download/v0.1.7/nexus-release.apk",
            "https://github.com/$owner/$repository/releases/download/v0.1.8/other.apk",
            "https://attacker@github.com/$owner/$repository/releases/download/v0.1.8/nexus-release.apk"
        )
        urls.forEach { url ->
            assertThrows(IOException::class.java) {
                parseLatestReleasePayload(
                    releasePayload(url = url),
                    "0.0.0-dev", 1L, owner, repository
                )
            }
        }
    }

    @Test
    fun safelyBoundsReleaseNotes() {
        val result = parseLatestReleasePayload(
            releasePayload(body = "x".repeat(3_000)),
            "0.0.0-dev", 1L, owner, repository
        ) as UpdateCheckResult.Available
        assertEquals(2_000, result.update.releaseNotes.length)
    }

    @Test
    fun malformedJsonProducesAnActionableIoError() {
        val error = assertThrows(IOException::class.java) {
            parseLatestReleasePayload("{not-json", "0.0.0-dev", 1L, owner, repository)
        }
        assertTrue(error.message!!.contains("invalid release metadata", ignoreCase = true))
    }

    @Test
    fun exactReleaseUrlRejectsQueryStringAndFragment() {
        assertFalse(
            isTrustedReleaseUrl(
                "https://github.com/$owner/$repository/releases/download/v0.1.8/nexus-release.apk?redirect=1",
                "v0.1.8", owner, repository
            )
        )
        assertFalse(
            isTrustedReleaseUrl(
                "https://github.com/$owner/$repository/releases/download/v0.1.8/nexus-release.apk#other",
                "v0.1.8", owner, repository
            )
        )
    }

    private fun releasePayload(
        tag: String = "v0.1.8",
        assets: JSONArray = JSONArray().put(asset()),
        draft: Boolean = false,
        prerelease: Boolean = false,
        body: String = "Bug fixes and reliability improvements"
    ): String = JSONObject()
        .put("tag_name", tag)
        .put("draft", draft)
        .put("prerelease", prerelease)
        .put("body", body)
        .put("assets", assets)
        .toString()

    private fun asset(
        name: String = "nexus-release.apk",
        url: String = "https://github.com/$owner/$repository/releases/download/v0.1.8/nexus-release.apk",
        digestValue: String = "sha256:$digest",
        size: Long = 8_000L,
        contentType: String = "application/vnd.android.package-archive",
        state: String = "uploaded"
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("browser_download_url", url)
        .put("digest", digestValue)
        .put("size", size)
        .put("content_type", contentType)
        .put("state", state)
}
