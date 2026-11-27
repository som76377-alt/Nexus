package com.nexus.music.catalog

import com.nexus.music.ui.HomeDiscoveryCategory
import com.nexus.music.ui.HomeDiscoveryGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDiscoveryEngineTest {
    private val groups = listOf(
        HomeDiscoveryGroup(
            id = "genres",
            title = "Genres",
            categories = listOf(
                HomeDiscoveryCategory("jazz", "Jazz", "jazz music", YoutubeSearchScope.MUSIC_SONGS),
                HomeDiscoveryCategory("indie", "Indie", "independent music", YoutubeSearchScope.MUSIC_SONGS),
                HomeDiscoveryCategory("classical", "Classical", "classical music", YoutubeSearchScope.MUSIC_SONGS),
                HomeDiscoveryCategory("ambient", "Ambient", "ambient music", YoutubeSearchScope.EVERYTHING)
            )
        )
    )

    @Test
    fun initialPlanCombinesListenerSignalsWithExplorationAndIsSeeded() {
        val recent = listOf(track("recent", "Recent Artist"))
        val saved = listOf(track("saved", "Saved Artist"))
        val memory = emptyMemory()

        val first = HomeDiscoveryEngine.createInitialPlan(groups, recent, saved, memory, seed = 42L)
        val repeated = HomeDiscoveryEngine.createInitialPlan(groups, recent, saved, memory, seed = 42L)

        assertEquals(first, repeated)
        assertTrue(first.any { it.query.contains("similar to", ignoreCase = true) })
        assertTrue(first.any { it.query.contains("emerging artists", ignoreCase = true) })
        assertTrue(first.size <= 9)
    }

    @Test
    fun resultsExpandIntoShelvesForPreviouslyUnknownArtists() {
        val results = listOf(
            track("one", "Known Artist"),
            track("two", "New Artist One"),
            track("three", "New Artist Two"),
            track("four", "New Artist One"),
            track("five", "Dismissed Artist")
        )
        val expanded = HomeDiscoveryEngine.expandFromResults(
            results = results,
            knownArtists = setOf("Known Artist"),
            existingShelfIds = emptySet(),
            memory = emptyMemory().copy(dislikedArtists = setOf("dismissed artist")),
            currentShelfCount = 5,
            seed = 7L
        )

        assertEquals(2, expanded.size)
        assertTrue(expanded.all { it.title.startsWith("A new voice:") })
        assertTrue(expanded.none { it.title.contains("Known Artist") || it.title.contains("Dismissed Artist") })
    }

    @Test
    fun rankingAvoidsDismissedTracksAndPrefersUnseenItems() {
        val unseen = track("unseen", "New Artist")
        val previouslyRecommended = track("seen", "Another Artist")
        val dismissed = track("dismissed", "Hidden Artist")
        val memory = emptyMemory().copy(
            recommendedTrackUrls = setOf(previouslyRecommended.url),
            dismissedTrackUrls = setOf(dismissed.url),
            dislikedArtists = setOf("hidden artist")
        )

        val ranked = HomeDiscoveryEngine.rankTracks(
            tracks = listOf(previouslyRecommended, dismissed, unseen),
            memory = memory,
            recentTracks = emptyList(),
            savedTracks = emptyList(),
            seed = 1L
        )

        assertEquals(unseen, ranked.first())
        assertFalse(ranked.contains(dismissed))
        assertTrue(ranked.contains(previouslyRecommended))
    }

    private fun emptyMemory() = HomeDiscoveryMemory(
        playedArtistWeights = emptyMap(),
        dismissedTrackUrls = emptySet(),
        dislikedArtists = emptySet(),
        recommendedTrackUrls = emptySet()
    )

    private fun track(url: String, artist: String) = CatalogTrack(
        url = "https://www.youtube.com/watch?v=$url",
        title = "Track $url",
        artist = artist,
        thumbnailUrl = null,
        durationSeconds = 180L,
        viewCount = 100L
    )
}
