package com.nexus.music.blend

import com.nexus.music.catalog.CatalogTrack
import com.nexus.music.library.PlayHistoryEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteProfileTest {
    private fun track(id: Int, artist: String) = CatalogTrack(
        url = "https://music.example/$id",
        title = "Track $id",
        artist = artist,
        thumbnailUrl = null,
        durationSeconds = 180L,
        viewCount = null
    )

    @Test
    fun aThirtyDayOldListenHasHalfTheWeightOfANewListen() {
        val now = 1_800_000_000_000L
        val profile = TasteProfileComputer.compute(
            listOf(
                PlayHistoryEvent(track(1, "Current"), now),
                PlayHistoryEvent(track(2, "Older"), now - 30L * 86_400_000L)
            ),
            now
        )
        assertEquals(2, profile.artists.size)
        assertEquals(1.0, profile.artists[0].score, 0.0001)
        assertEquals(0.5, profile.artists[1].score, 0.0001)
    }

    @Test
    fun onlyTopOneHundredAggregatedArtistsAreKept() {
        val now = 1_800_000_000_000L
        val events = (0 until 120).map { PlayHistoryEvent(track(it, "Artist $it"), now - it * 1000L) }
        assertEquals(100, TasteProfileComputer.compute(events, now).artists.size)
    }

    @Test
    fun blendStartsWithSharedArtistAndAlternatesTwoProfilesThenFillsRelated() {
        val first = TasteProfile(now(), listOf(ArtistTaste("Shared", 1.0), ArtistTaste("A", 0.9), ArtistTaste("C", 0.8)))
        val second = TasteProfile(now(), listOf(ArtistTaste("Shared", 0.8), ArtistTaste("B", 0.9), ArtistTaste("D", 0.7)))
        val byArtist = mapOf(
            "Shared" to listOf(track(1, "Shared")),
            "A" to listOf(track(2, "A")),
            "B" to listOf(track(3, "B")),
            "C" to listOf(track(4, "C")),
            "D" to listOf(track(5, "D"))
        )
        val related = (6..55).map { track(it, "Related") }
        val result = BlendGenerator.generate(first, second, byArtist, related)
        assertEquals(50, result.size)
        assertEquals("Shared", result[0].artist)
        assertEquals("A", result[1].artist)
        assertEquals("B", result[2].artist)
        assertTrue(result.map { it.url }.distinct().size == 50)
    }

    private fun now() = 1_800_000_000_000L
}
