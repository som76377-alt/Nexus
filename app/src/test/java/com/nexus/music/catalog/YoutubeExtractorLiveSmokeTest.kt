package com.nexus.music.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException

/**
 * Opt-in network smoke test. It intentionally opens the primary circuit so a green result
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

        val policy = ExtractorFailoverPolicy().apply { primaryFailed() }
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
        } catch (blocked: SignInConfirmNotBotException) {
            skipWhenEveryFallbackIsUnavailable(blocked)
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
        fun printFailure(error: Throwable, depth: Int = 0) {
            if (depth > 6) return
            System.err.println(
                "Extractor fallback diagnostic: ${"  ".repeat(depth)}" +
                    "${error.javaClass.simpleName}: ${error.message}"
            )
            error.cause?.takeIf { it !== error }?.let { printFailure(it, depth + 1) }
            error.suppressed.forEach { printFailure(it, depth + 1) }
        }
        printFailure(blocked)
        assumeTrue(
            "NewPipe was challenged and neither Piped nor Invidious completed extraction; " +
                "live fallback health is unavailable from this CI runner.",
            false
        )
        throw blocked
    }
}
