package com.nexus.music.recommendations

import com.nexus.music.catalog.CatalogTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationMixerTest {
    private fun track(id: Int) = CatalogTrack(
        url = "https://music.example/track/$id",
        title = "Track $id",
        artist = "Artist $id",
        thumbnailUrl = null,
        durationSeconds = 180L,
        viewCount = null
    )

    private val genres = (0 until 6).associate { genre ->
        "genre_$genre" to (0 until 30).map { track(genre * 100 + it) }
    }

    @Test
    fun batchHasTwentyTracksAndAtLeastFiveGenreBuckets() {
        val result = RecommendationMixer.mix(
            tasteTracks = (600 until 660).map(::track),
            genreTracks = genres,
            seed = 41L,
            excludedIds = emptySet()
        )
        assertEquals(20, result.size)
        val genreIds = genres.filterValues { bucket ->
            result.any { candidate -> bucket.any { it.url == candidate.url } }
        }.keys
        assertTrue(genreIds.size >= 5)
    }

    @Test
    fun differentSeedsHaveAtMostThirtyPercentOverlap() {
        val taste = (600 until 660).map(::track)
        val first = RecommendationMixer.mix(taste, genres, seed = 11L, excludedIds = emptySet()).map { it.url }.toSet()
        val second = RecommendationMixer.mix(taste, genres, seed = 97L, excludedIds = emptySet()).map { it.url }.toSet()
        val overlap = first.intersect(second).size
        assertTrue("Overlap was $overlap of 20", overlap <= 6)
    }

    @Test
    fun excludesPreviouslyShownIds() {
        val excluded = (600 until 620).map { track(it).url }.toSet()
        val result = RecommendationMixer.mix(
            tasteTracks = (600 until 660).map(::track),
            genreTracks = genres,
            seed = 77L,
            excludedIds = excluded
        )
        assertTrue(result.none { it.url in excluded })
    }
}
