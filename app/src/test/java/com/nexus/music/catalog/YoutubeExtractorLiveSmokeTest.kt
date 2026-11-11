package com.nexus.music.catalog

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import java.util.concurrent.TimeUnit

/**
 * Opt-in network smoke test. It intentionally opens the NewPipe circuit so a green result
 * proves a server-side fallback completed search AND audio resolution, and the returned media
 * URL served bytes to the same user-agent used by Nexus's Media3 player.
 * Run with -Pnexus.liveExtractorTests=true.
 */
class YoutubeExtractorLiveSmokeTest {
    @Test
    fun fallbackSearchAndAudioResolutionWorkAgainstLiveSource() = runBlocking {
        assumeTrue(
            "Set -Dnexus.liveExtractorTests=true to run the live extractor test.",
            System.getProperty("nexus.liveExtractorTests") == "true"
        )

        val policy = ExtractorFailoverPolicy(cooldownMillis = 10 * 60 * 1000L)
            .apply { primaryFailed() }
        val repository = YoutubeCatalogRepository(
            downloader = OkHttpDownloader(),
            pipedFallback = PipedFallbackExtractor(),
            invidiousFallback = InvidiousFallbackExtractor(),
            failoverPolicy = policy
        )
        val results = try {
            repository.search("lofi hip hop", YoutubeSearchScope.MUSIC_SONGS)
        } catch (blocked: SignInConfirmNotBotException) {
            skipWhenEveryFallbackIsUnavailable(blocked, repository.diagnosticsSummary())
        }
        assertFalse("Expected at least one public music result from a fallback.", results.isEmpty())
        assertFallbackWasUsed("search", repository.diagnosticsSummary())

        val resolved = try {
            repository.resolveAudio(results.first().url)
        } catch (error: Exception) {
            throw AssertionError(
                "Fallback audio resolution failed after search succeeded. " +
                    "Provider diagnostics: ${repository.diagnosticsSummary()}",
                error
            )
        }

        assertTrue("Expected an HTTPS audio URL.", resolved.audioUrl.startsWith("https://"))
        assertTrue("Expected a title.", resolved.title.isNotBlank())
        assertFallbackWasUsed("audio resolution", repository.diagnosticsSummary())
        assertResolvedAudioUrlServesBytes(resolved.audioUrl, repository.diagnosticsSummary())
    }

    private fun assertResolvedAudioUrlServesBytes(url: String, diagnostics: String) {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "unknown"
        System.out.println("Resolved media host=$host; $diagnostics")
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", PLAYER_USER_AGENT)
            .header("Range", "bytes=0-1023")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            assertTrue(
                "Resolved audio URL from host $host returned HTTP ${response.code}. " +
                    "Provider diagnostics: $diagnostics",
                response.isSuccessful
            )
            val bytes = response.body?.byteStream()?.readNBytes(1024) ?: ByteArray(0)
            assertTrue(
                "Resolved audio URL returned no media bytes (Content-Type: " +
                    "${response.header("Content-Type")}).",
                bytes.isNotEmpty()
            )
        }
    }

    private fun assertFallbackWasUsed(stage: String, diagnostics: String) {
        assertTrue(
            "The $stage completed without proving that Piped or Invidious was used. " +
                "Provider diagnostics: $diagnostics",
            diagnostics.contains("Piped: OK") || diagnostics.contains("Invidious: OK")
        )
    }

    private fun skipWhenEveryFallbackIsUnavailable(
        blocked: SignInConfirmNotBotException,
        diagnostics: String
    ): Nothing {
        System.err.println("Live fallback provider diagnostics:\\n$diagnostics")
        blocked.suppressed.forEach { error ->
            System.err.println("Fallback failure: ${error.javaClass.simpleName}: ${error.message}")
        }
        assumeTrue(
            "NewPipe was challenged and neither Piped nor Invidious completed search; " +
                "live fallback search cannot be evaluated from this CI runner. " +
                "Provider diagnostics: $diagnostics",
            false
        )
        throw blocked
    }

    companion object {
        private const val PLAYER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 Nexus/0.1"
    }
}
