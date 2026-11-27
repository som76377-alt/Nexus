package com.nexus.music.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class YoutubeCatalogRepositoryTest {
    @Test
    fun repositoryCachesSearchesByNormalizedQueryAndScope() = runBlocking {
        val adapter = CountingAdapter()
        val repository = YoutubeCatalogRepository(adapter = adapter)

        val first = repository.search("  ambient   jazz ", YoutubeSearchScope.MUSIC_SONGS)
        val cached = repository.search("ambient jazz", YoutubeSearchScope.MUSIC_SONGS)
        val differentScope = repository.search("ambient jazz", YoutubeSearchScope.EVERYTHING)

        assertEquals(listOf(sampleTrack()), first)
        assertEquals(first, cached)
        assertEquals(listOf(sampleTrack()), differentScope)
        assertEquals(2, adapter.searchCalls)
        assertEquals(listOf("ambient jazz", "ambient jazz"), adapter.queries)
    }

    private class CountingAdapter : StreamProviderAdapter {
        override val provider = StreamProvider.NEWPIPE
        var searchCalls = 0
        val queries = mutableListOf<String>()

        override suspend fun search(
            query: String,
            scope: YoutubeSearchScope
        ): List<CatalogTrack> {
            searchCalls++
            queries += query
            return listOf(sampleTrack())
        }

        override suspend fun resolveAudio(videoUrl: String): ResolvedAudio {
            throw IOException("Not used by this test.")
        }
    }
}

private fun sampleTrack() = CatalogTrack(
    url = "https://youtube.com/watch?v=abcdefghijk",
    title = "Ambient Track",
    artist = "Artist",
    thumbnailUrl = null,
    durationSeconds = 180L,
    viewCount = 1_000L
)
