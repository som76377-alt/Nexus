package com.nexus.music.catalog

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HomeShelfRecoveryTest {
    @Test fun rerollPrefersUnseenTracksBeforeRepeatingHistory() {
        val pool = (1..16).map(::track)
        val history = pool.take(10).map { it.url }
        val selected = HomeRerollSelector.select(pool, history, 8, Random(7))
        assertEquals(8, selected.size)
        assertTrue(selected.all { it.url !in history })
    }

    @Test fun rerollRemainsUsefulAfterEveryTrackHasBeenSeen() {
        val pool = (1..12).map(::track)
        val selected = HomeRerollSelector.select(pool, pool.map { it.url }, 8, Random(0))
        assertEquals(pool.take(8).map { it.url }, selected.map { it.url })
    }

    @Test fun rerollDeduplicatesPoolAndNeverReturnsMoreThanAvailable() {
        val first = track(1)
        val selected = HomeRerollSelector.select(listOf(first, first, track(2), track(3)), emptyList(), 8, Random(1))
        assertEquals(3, selected.size)
        assertEquals(3, selected.map { it.url }.toSet().size)
    }

    @Test fun oneShelfFailureAndTimeoutDoNotPreventLaterShelves() = runBlocking {
        val shelves = listOf(
            HomeShelfSpec("failure", "Failure", "failure"),
            HomeShelfSpec("timeout", "Timeout", "timeout"),
            HomeShelfSpec("success", "Success", "success")
        )
        val completed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        HomeShelfRefreshRunner(25L).run(
            shelves,
            fetch = { shelf ->
                when (shelf.id) {
                    "failure" -> error("offline")
                    "timeout" -> { delay(100L); listOf(track(99)) }
                    else -> listOf(track(100))
                }
            },
            onResult = { shelf, _ -> completed += shelf.id },
            onFailure = { shelf, _ -> failed += shelf.id }
        )
        assertEquals(listOf("success"), completed)
        assertEquals(listOf("failure", "timeout"), failed)
    }

    @Test fun refreshRunnerNeverRunsMoreThanOneBackgroundShelfAtOnce() = runBlocking {
        var active = 0
        var maximum = 0
        val shelves = (1..4).map { HomeShelfSpec("s$it", "Shelf $it", "query $it") }
        HomeShelfRefreshRunner(1_000L).run(
            shelves,
            fetch = {
                active++
                maximum = maxOf(maximum, active)
                delay(5L)
                active--
                listOf(track(1))
            },
            onResult = { _, _ -> }
        )
        assertEquals(1, maximum)
    }


    @Test fun emptyRefreshPreservesUsefulStaleRecommendations() {
        val pool = (1..12).map(::track)
        val displayed = pool.take(8)
        val previous = HomeShelfSnapshot(100L, pool, displayed, displayed.map { it.url })
        val refreshed = refreshedHomeShelfSnapshot(
            previous = previous,
            fetchedAtEpochMs = 200L,
            tracks = emptyList(),
            poolLimit = 40,
            visibleLimit = 8,
            historyLimit = 120
        )
        assertEquals(200L, refreshed.fetchedAtEpochMs)
        assertEquals(pool, refreshed.pool)
        assertEquals(displayed, refreshed.displayedTracks)
        assertEquals(previous.history, refreshed.history)
    }

    private fun track(id: Int) = CatalogTrack(
        url = "https://www.youtube.com/watch?v=track$id",
        title = "Track $id",
        artist = "Artist",
        thumbnailUrl = null,
        durationSeconds = 180L,
        viewCount = id.toLong()
    )
}
