package com.nexus.music.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException

/**
 * Opt-in network smoke test. It intentionally opens the NewPipe circuit so a green result
 * proves at least one server-side fallback completed search AND audio resolution.
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
            skipWhenEveryFallbackIsUnavailable(blocked)
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
    }

    private fun assertFallbackWasUsed(stage: String, diagnostics: String) {
        assertTrue(
            "The $stage completed without proving that Piped or Invidious was used. " +
                "Provider diagnostics: $diagnostics",
            diagnostics.contains("Piped: OK") || diagnostics.contains("Invidious: OK")
        )
    }

    private fun skipWhenEveryFallbackIsUnavailable(blocked: SignInConfirmNotBotException): Nothing {
        assumeTrue(
            "NewPipe was challenged and neither Piped nor Invidious completed search; " +
                "live fallback search cannot be evaluated from this CI runner.",
            false
        )
        throw blocked
    }
}
