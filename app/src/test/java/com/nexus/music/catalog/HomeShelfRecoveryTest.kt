package com.nexus.music.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HomeShelfRecoveryTest {
    @Test fun rerollPrefersUnseenTracksBeforeRepeatingHistory() {
        val pool = (1..18).map(::track)
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
            fetch = { shelf, timeoutMs ->
                withTimeout(timeoutMs) { when (shelf.id) {
                    "failure" -> error("offline")
                    "timeout" -> { delay(100L); listOf(track(99)) }
                    else -> listOf(track(100))
                } }
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
            fetch = { _, timeoutMs ->
                withTimeout(timeoutMs) {
                active++
                maximum = maxOf(maximum, active)
                delay(5L)
                active--
                listOf(track(1))
                }
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


    @Test fun foregroundRequestPreemptsHomeWorkAndARefreshCanRecoverIt() = runBlocking {
        val queue = PriorityAdmissionQueue()
        val shelfStarted = CompletableDeferred<Unit>()
        var firstAttempt = true
        val rendered = mutableListOf<String>()
        val shelfA = HomeShelfSpec("a", "A", "query a")
        val shelfB = HomeShelfSpec("b", "B", "query b")

        val home = launch {
            HomeShelfRefreshRunner(1_000L).run(
                shelves = listOf(shelfA, shelfB),
                fetch = { shelf, timeoutMs ->
                    queue.withLease(ExtractorWorkPriority.BACKGROUND) {
                        withTimeout(timeoutMs) {
                        if (shelf.id == "a" && firstAttempt) {
                            firstAttempt = false
                            shelfStarted.complete(Unit)
                            awaitCancellation()
                        }
                        listOf(track(if (shelf.id == "a") 1 else 2))
                        }
                    }
                },
                onResult = { shelf, _ -> rendered += shelf.id }
            )
        }
        shelfStarted.await()

        val foreground = launch {
            queue.withLease(ExtractorWorkPriority.FOREGROUND) { }
        }
        withTimeout(2_000L) {
            foreground.join()
            home.join()
        }
        assertEquals(listOf("b"), rendered)

        // MainActivity schedules this refresh after foreground search or playback finishes.
        HomeShelfRefreshRunner(1_000L).run(
            shelves = listOf(shelfA),
            fetch = { shelf, timeoutMs ->
                queue.withLease(ExtractorWorkPriority.BACKGROUND) {
                    withTimeout(timeoutMs) { listOf(track(3)) }
                }
            },
            onResult = { shelf, _ -> rendered += shelf.id }
        )
        assertEquals(listOf("b", "a"), rendered)
    }

    @Test
    fun tapWhileHomeLoadsCancelsOnlyExtractionChildThenAllShelvesRecover() = runBlocking {
        val queue = PriorityAdmissionQueue()
        val extractionStarted = CompletableDeferred<Unit>()
        val secondShelfStarted = CompletableDeferred<Unit>()
        var firstAttempt = true
        val rendered = mutableListOf<String>()
        val shelves = listOf(
            HomeShelfSpec("a", "A", "query a"),
            HomeShelfSpec("b", "B", "query b")
        )
        val coordinator = SearchRequestCoordinator<CatalogTrack>(
            requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            promoteRequest = queue::promote
        )
        val fetch: suspend (HomeShelfSpec, Long) -> List<CatalogTrack> = { shelf, timeoutMs ->
            coordinator.search(
                query = shelf.query,
                scope = YoutubeSearchScope.MUSIC_SONGS,
                priority = ExtractorWorkPriority.BACKGROUND,
                operationTimeoutMs = timeoutMs,
                loader = {
                    val context = currentCoroutineContext()[ExtractorPriorityContext]!!
                    queue.withLease(context.priority, context.ownerJob) {
                        if (shelf.id == "a" && firstAttempt) {
                            firstAttempt = false
                            extractionStarted.complete(Unit)
                            awaitCancellation()
                        }
                        if (shelf.id == "b") secondShelfStarted.complete(Unit)
                        listOf(track(if (shelf.id == "a") 1 else 2))
                    }
                }
            )
        }

        val home = launch {
            HomeShelfRefreshRunner(6_000L).run(
                shelves = shelves,
                fetch = fetch,
                onResult = { shelf, _ -> rendered += shelf.id }
            )
        }
        extractionStarted.await()

        val tap = launch {
            queue.withLease(ExtractorWorkPriority.FOREGROUND) { }
        }
        withTimeout(2_000L) {
            tap.join()
            secondShelfStarted.await()
        }
        assertFalse(home.isCancelled)
        home.join()
        assertEquals(listOf("b"), rendered)

        // MainActivity restarts stale shelves after the tap finishes; both shelves must recover.
        HomeShelfRefreshRunner(6_000L).run(
            shelves = shelves,
            fetch = fetch,
            onResult = { shelf, _ -> rendered += shelf.id }
        )
        assertEquals(listOf("b", "a", "b"), rendered)
    }

    private fun track(id: Int) = CatalogTrack(
        url = "https://www.youtube.com/watch?v=track$id",
        title = "Track $id",
        artist = "Artist",
        thumbnailUrl = null,
        durationSeconds = 180L,
        viewCount = id.toLong()
    )

    @Test
    fun queuedShelfGetsItsFullTimeoutAfterEightSecondForegroundLease() = runTest {
        val queue = PriorityAdmissionQueue()
        val foregroundEntered = CompletableDeferred<Unit>()
        val shelf = HomeShelfSpec("queued", "Queued", "query")
        val completed = mutableListOf<String>()
        val failed = mutableListOf<String>()

        val foreground = launch {
            queue.withLease(ExtractorWorkPriority.FOREGROUND) {
                foregroundEntered.complete(Unit)
                delay(8_000L)
            }
        }
        foregroundEntered.await()

        val home = launch {
            HomeShelfRefreshRunner(6_000L).run(
                shelves = listOf(shelf),
                fetch = { _, timeoutMs ->
                    queue.withLease(ExtractorWorkPriority.BACKGROUND) {
                        withTimeout(timeoutMs) {
                            delay(5_000L)
                            listOf(track(88))
                        }
                    }
                },
                onResult = { spec, _ -> completed += spec.id },
                onFailure = { spec, _ -> failed += spec.id }
            )
        }
        runCurrent()
        advanceTimeBy(8_000L)
        runCurrent()
        foreground.join()
        home.join()

        assertEquals(listOf("queued"), completed)
        assertTrue(failed.isEmpty())
    }
}
