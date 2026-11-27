package com.nexus.music.catalog

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RepositorySearchSourceTest {
    @Test
    fun delegatesFirstPageToExistingCatalogueFacadeWithoutChangingResults() = runTest {
        val expected = listOf(
            CatalogTrack(
                url = "https://www.youtube.com/watch?v=abc123",
                title = "Example track",
                artist = "Example artist",
                thumbnailUrl = null,
                durationSeconds = 180L,
                viewCount = 42L
            )
        )
        val adapter = FakeAdapter(expected)
        val source = RepositorySearchSource(YoutubeCatalogRepository(adapter = adapter))

        val page = source.search("ambient", YoutubeSearchScope.MUSIC_SONGS, cursor = null)

        assertEquals("ambient", adapter.query)
        assertEquals(YoutubeSearchScope.MUSIC_SONGS, adapter.scope)
        assertEquals(expected, page.items)
        assertNull(page.nextCursor)
        assertFalse(page.hasMore)
        assertEquals(0, adapter.resolveCalls)
    }

    private class FakeAdapter(
        private val results: List<CatalogTrack>
    ) : StreamProviderAdapter {
        override val provider = StreamProvider.PIPED
        var query: String? = null
        var scope: YoutubeSearchScope? = null
        var resolveCalls = 0

        override suspend fun search(
            query: String,
            scope: YoutubeSearchScope
        ): List<CatalogTrack> {
            this.query = query
            this.scope = scope
            return results
        }

        override suspend fun resolveAudio(videoUrl: String): ResolvedAudio {
            resolveCalls++
            throw UnsupportedOperationException("Not used by search tests.")
        }
    }
}
