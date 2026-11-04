package com.nexus.music.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in network smoke test. It is skipped in ordinary CI to avoid making builds depend on
 * YouTube availability. Run with -Pnexus.liveExtractorTests=true when intentionally testing
 * the live extractor against public content.
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

        val resolved = repository.resolveAudio(results.first().url)
        assertTrue("Expected an HTTP audio URL.", resolved.audioUrl.startsWith("https://"))
        assertTrue("Expected a title.", resolved.title.isNotBlank())
    }
}
