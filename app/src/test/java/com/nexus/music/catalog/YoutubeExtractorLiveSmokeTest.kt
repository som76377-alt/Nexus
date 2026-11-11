package com.nexus.music.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException

/**
 * Opt-in network smoke test. It is skipped in ordinary CI to avoid making builds depend on
 * YouTube availability. Run with -Pnexus.liveExtractorTests=true when intentionally testing
 * the live extractor against public content.
 *
 * YouTube may challenge anonymous requests from shared CI/datacenter IPs. That is an external
 * access restriction, not evidence that the app's extractor is broken, so only that known
 * condition is reported as an assumption/skip. Other extraction failures remain test failures.
 */
class YoutubeExtractorLiveSmokeTest {
    @Test
    fun publicMusicSearchAndAudioResolutionWorkAgainstLiveSource() = runBlocking {
        assumeTrue(
            "Set -Dnexus.liveExtractorTests=true to run the live YouTube test.",
            System.getProperty("nexus.liveExtractorTests") == "true"
        )

        val repository = YoutubeCatalogRepository()
        val results = repository.search("lofi hip hop", YoutubeSearchScope.MUSIC_SONGS)
        assertFalse("Expected at least one public music result.", results.isEmpty())

        val resolved = try {
            repository.resolveAudio(results.first().url)
        } catch (blocked: SignInConfirmNotBotException) {
            assumeTrue(
                "YouTube challenged anonymous access from this CI IP; live stream resolution " +
                    "cannot be evaluated on this runner.",
                false
            )
            throw blocked
        }

        assertTrue("Expected an HTTP audio URL.", resolved.audioUrl.startsWith("https://"))
        assertTrue("Expected a title.", resolved.title.isNotBlank())
    }
}
