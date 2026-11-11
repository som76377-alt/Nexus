package com.nexus.music.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException

/**
 * Opt-in network smoke test. It is skipped in ordinary CI to avoid making builds depend on
 * public extractor availability. Run with -Pnexus.liveExtractorTests=true when intentionally
 * testing the live NewPipe/Piped/Invidious provider chain.
 *
 * Shared CI IPs can receive a YouTube bot challenge. If all fallback routes are also unavailable,
 * that specific external restriction is reported as an assumption/skip rather than a parser pass.
 */
class YoutubeExtractorLiveSmokeTest {
    @Test
    fun publicMusicSearchAndAudioResolutionWorkAgainstLiveSource() = runBlocking {
        assumeTrue(
            "Set -Dnexus.liveExtractorTests=true to run the live extractor test.",
            System.getProperty("nexus.liveExtractorTests") == "true"
        )

        val repository = YoutubeCatalogRepository()
        val results = try {
            repository.search("lofi hip hop", YoutubeSearchScope.MUSIC_SONGS)
        } catch (blocked: SignInConfirmNotBotException) {
            skipForSharedRunnerChallenge(blocked)
        }
        assertFalse("Expected at least one public music result.", results.isEmpty())

        val resolved = try {
            repository.resolveAudio(results.first().url)
        } catch (blocked: SignInConfirmNotBotException) {
            skipForSharedRunnerChallenge(blocked)
        }

        assertTrue("Expected an HTTPS audio URL.", resolved.audioUrl.startsWith("https://"))
        assertTrue("Expected a title.", resolved.title.isNotBlank())
    }

    private fun skipForSharedRunnerChallenge(blocked: SignInConfirmNotBotException): Nothing {
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
            "YouTube challenged this CI IP and neither fallback route could complete the " +
                "request; live extraction cannot be evaluated on this runner.",
            false
        )
        throw blocked
    }
}
