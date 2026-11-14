package com.nexus.music.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

class SearchSessionTest {
    @Test
    fun firstSuccessfulSourcePublishesWithoutWaitingForSlowSecondary() = runTest {
        val primaryTrack = track(1)
        val manager = SearchSessionManager(
            parentScope = backgroundScope,
            sources = listOf(
                FakeSearchSource(StreamProvider.NEWPIPE) { _, _, _ ->
                    delay(100L)
                    SearchPage(listOf(primaryTrack), null, hasMore = false)
                },
                FakeSearchSource(StreamProvider.PIPED) { _, _, _ ->
                    delay(5_000L)
                    SearchPage(listOf(track(2)), null, hasMore = false)
                }
            ),
            dispatcher = StandardTestDispatcher(testScheduler),
            sourceTimeoutMs = 6_000L
        )

        val session = manager.start("ambient", YoutubeSearchScope.EVERYTHING)
        runCurrent()
        advanceTimeBy(100L)
        runCurrent()

        assertEquals(listOf(primaryTrack), session.state.value.items)
        assertTrue(session.state.value.isLoadingFirstPage)
    }

    @Test
    fun failedSourceDoesNotBlockAnotherSourceFromPublishing() = runTest {
        val result = track(2)
        val manager = SearchSessionManager(
            parentScope = backgroundScope,
            sources = listOf(
                FakeSearchSource(StreamProvider.NEWPIPE) { _, _, _ ->
                    throw IOException("primary unavailable")
                },
                FakeSearchSource(StreamProvider.PIPED) { _, _, _ ->
                    delay(100L)
                    SearchPage(listOf(result), null, hasMore = false)
                }
            ),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        val session = manager.start("ambient", YoutubeSearchScope.EVERYTHING)
        advanceTimeBy(100L)
        runCurrent()

        assertEquals(listOf(result), session.state.value.items)
        assertTrue(StreamProvider.NEWPIPE in session.state.value.failedSources)
        assertFalse(session.state.value.allSourcesFailed)
    }

    @Test
    fun startingANewQueryCancelsOldSessionAndPreventsStaleEmission() = runTest {
        var oldCancelled = false
        val manager = SearchSessionManager(
            parentScope = backgroundScope,
            sources = listOf(
                FakeSearchSource(StreamProvider.NEWPIPE) { query, _, _ ->
                    if (query == "old") {
                        try {
                            delay(1_000L)
                            SearchPage(listOf(track(1)), null, hasMore = false)
                        } catch (error: CancellationException) {
                            oldCancelled = true
                            throw error
                        }
                    } else {
                        delay(100L)
                        SearchPage(listOf(track(2)), null, hasMore = false)
                    }
                }
            ),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        val oldSession = manager.start("old", YoutubeSearchScope.EVERYTHING)
        runCurrent()
        advanceTimeBy(50L)
        val newSession = manager.start("new", YoutubeSearchScope.EVERYTHING)
        advanceTimeBy(100L)
        runCurrent()
        advanceTimeBy(2_000L)
        runCurrent()

        assertTrue(oldCancelled)
        assertTrue(oldSession.state.value.items.isEmpty())
        assertEquals(listOf(track(2)), newSession.state.value.items)
    }

    @Test
    fun overlappingProviderPagesKeepUniqueIdsAndAnAppendOnlyPrefix() = runTest {
        repeat(25) { seed ->
            val random = Random(seed)
            val ids = (1..30).map(::videoId)
            val sourceDefinitions = listOf(
                StreamProvider.NEWPIPE,
                StreamProvider.PIPED,
                StreamProvider.INVIDIOUS
            ).mapIndexed { index, provider ->
                val firstPage = ids.shuffled(random).take(10).map(::trackFromId)
                val secondPage = ids.shuffled(random).take(10).map(::trackFromId)
                provider to (firstPage to secondPage)
            }
            val sources = sourceDefinitions.mapIndexed { index, (provider, pages) ->
                FakeSearchSource(provider) { _, _, cursor ->
                    delay(index * 10L)
                    if (cursor == null) {
                        SearchPage(
                            pages.first,
                            SearchCursor(provider, "page-2"),
                            hasMore = true
                        )
                    } else {
                        SearchPage(pages.second, null, hasMore = false)
                    }
                }
            }
            val manager = SearchSessionManager(
                parentScope = backgroundScope,
                sources = sources,
                dispatcher = StandardTestDispatcher(testScheduler)
            )
            val session = manager.start("query-$seed", YoutubeSearchScope.EVERYTHING)
            val snapshots = mutableListOf<List<String>>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                session.state.collect { snapshot ->
                    snapshots += snapshot.items.mapNotNull { it.videoId }
                }
            }

            advanceUntilIdle()
            session.loadMore()
            advanceUntilIdle()
            collector.cancel()

            val finalIds = session.state.value.items.mapNotNull { it.videoId }
            assertEquals("seed=$seed", finalIds.size, finalIds.distinct().size)
            snapshots.zipWithNext().forEach { (before, after) ->
                assertEquals("seed=$seed", before, after.take(before.size))
            }
        }
    }


    @Test
    fun allSourcesFailedIsTrueOnlyWhenEveryFirstPageFails() = runTest {
        val manager = SearchSessionManager(
            parentScope = backgroundScope,
            sources = listOf(
                FakeSearchSource(StreamProvider.NEWPIPE) { _, _, _ -> throw IOException("offline") },
                FakeSearchSource(StreamProvider.PIPED) { _, _, _ -> throw IOException("offline") }
            ),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        val session = manager.start("ambient", YoutubeSearchScope.EVERYTHING)
        advanceUntilIdle()

        assertTrue(session.state.value.allSourcesFailed)
        assertEquals(2, session.state.value.failedSources.size)
    }

    @Test
    fun cursorFromAnotherProviderIsRejectedAndNeverReused() = runTest {
        val calls = mutableListOf<SearchCursor?>()
        val source = FakeSearchSource(StreamProvider.NEWPIPE) { _, _, cursor ->
            calls += cursor
            SearchPage(
                listOf(track(1)),
                SearchCursor(StreamProvider.PIPED, "foreign-page"),
                hasMore = true
            )
        }
        val manager = SearchSessionManager(
            parentScope = backgroundScope,
            sources = listOf(source),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        val session = manager.start("ambient", YoutubeSearchScope.EVERYTHING)
        advanceUntilIdle()
        session.loadMore()
        advanceUntilIdle()

        assertTrue(session.state.value.allSourcesFailed)
        assertTrue(session.state.value.items.isEmpty())
        assertEquals(listOf(null), calls)
    }

    @Test
    fun concurrencyNeverExceedsConfiguredLimit() = runTest {
        var active = 0
        var maximumActive = 0
        val sources = StreamProvider.entries.map { provider ->
            FakeSearchSource(provider) { _, _, _ ->
                active++
                maximumActive = maxOf(maximumActive, active)
                try {
                    delay(100L)
                    SearchPage(listOf(track(provider.ordinal + 1)), null, hasMore = false)
                } finally {
                    active--
                }
            }
        }
        val manager = SearchSessionManager(
            parentScope = backgroundScope,
            sources = sources,
            dispatcher = StandardTestDispatcher(testScheduler),
            maxConcurrentRequests = 2
        )

        manager.start("ambient", YoutubeSearchScope.EVERYTHING)
        advanceUntilIdle()

        assertEquals(2, maximumActive)
    }

    @Test
    fun emptyNoNewPageDoesNotPrematurelyExhaustPagination() = runTest {
        val cursors = mutableListOf<String?>()
        val source = FakeSearchSource(StreamProvider.NEWPIPE) { _, _, cursor ->
            val token = cursor?.token as? String
            cursors += token
            when (token) {
                null -> SearchPage(listOf(track(1)), SearchCursor(StreamProvider.NEWPIPE, "page-2"), true)
                "page-2" -> SearchPage(emptyList(), SearchCursor(StreamProvider.NEWPIPE, "page-3"), true)
                "page-3" -> SearchPage(listOf(track(2)), null, false)
                else -> error("Unexpected cursor: $token")
            }
        }
        val manager = SearchSessionManager(
            parentScope = backgroundScope,
            sources = listOf(source),
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        val session = manager.start("ambient", YoutubeSearchScope.EVERYTHING)
        advanceUntilIdle()

        assertEquals(listOf(track(1)), session.state.value.items)
        assertTrue(session.state.value.hasMore)

        session.loadMore()
        assertEquals(listOf(track(1)), session.state.value.items)
        assertTrue(session.state.value.hasMore)

        session.loadMore()
        assertEquals(listOf(track(1), track(2)), session.state.value.items)
        assertFalse(session.state.value.hasMore)
        assertEquals(listOf(null, "page-2", "page-3"), cursors)
    }

    @Test
    fun concurrentLoadMoreCallsAreSerializedIntoSeparateRounds() = runTest {
        var active = 0
        var maximumActive = 0
        var nextPage = 2
        val source = FakeSearchSource(StreamProvider.NEWPIPE) { _, _, cursor ->
            if (cursor == null) {
                SearchPage(listOf(track(1)), SearchCursor(StreamProvider.NEWPIPE, "page-2"), true)
            } else {
                active++
                maximumActive = maxOf(maximumActive, active)
                try {
                    delay(100L)
                    val current = nextPage++
                    if (current == 2) {
                        SearchPage(listOf(track(2)), SearchCursor(StreamProvider.NEWPIPE, "page-3"), true)
                    } else {
                        SearchPage(listOf(track(3)), null, false)
                    }
                } finally {
                    active--
                }
            }
        }
        val manager = SearchSessionManager(
            parentScope = backgroundScope,
            sources = listOf(source),
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        val session = manager.start("ambient", YoutubeSearchScope.EVERYTHING)
        advanceUntilIdle()

        val first = async { session.loadMore() }
        val second = async { session.loadMore() }
        advanceUntilIdle()
        first.await()
        second.await()

        assertEquals(1, maximumActive)
        assertEquals(listOf(track(1), track(2), track(3)), session.state.value.items)
        assertFalse(session.state.value.hasMore)
    }

    @Test
    fun paginationRoundDeadlineAbandonsSlowSourceAndDiscardsLateResult() = runTest {
        val source = FakeSearchSource(StreamProvider.PIPED) { _, _, cursor ->
            if (cursor == null) {
                SearchPage(listOf(track(1)), SearchCursor(StreamProvider.PIPED, "page-2"), true)
            } else {
                delay(5_000L)
                SearchPage(listOf(track(2)), null, hasMore = false)
            }
        }
        val manager = SearchSessionManager(
            parentScope = backgroundScope,
            sources = listOf(source),
            dispatcher = StandardTestDispatcher(testScheduler),
            roundDeadlineMs = 100L,
            sourceTimeoutMs = 10_000L
        )
        val session = manager.start("ambient", YoutubeSearchScope.EVERYTHING)
        advanceUntilIdle()

        val loading = async { session.loadMore() }
        advanceTimeBy(100L)
        runCurrent()
        loading.await()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(listOf(track(1)), session.state.value.items)
        assertFalse(session.state.value.hasMore)
        assertTrue(StreamProvider.PIPED in session.state.value.failedSources)
    }

    private class FakeSearchSource(
        override val provider: StreamProvider,
        private val handler: suspend (String, YoutubeSearchScope, SearchCursor?) -> SearchPage
    ) : SearchSource {
        override suspend fun search(
            query: String,
            scope: YoutubeSearchScope,
            cursor: SearchCursor?
        ): SearchPage = handler(query, scope, cursor)
    }

    private fun track(index: Int): CatalogTrack = trackFromId(videoId(index))

    private fun trackFromId(id: String): CatalogTrack = CatalogTrack(
        url = "https://www.youtube.com/watch?v=$id",
        title = "Track $id",
        artist = "Artist",
        thumbnailUrl = null,
        durationSeconds = 60L,
        viewCount = 10L
    )

    private fun videoId(index: Int): String = index.toString().padStart(11, '0')
}
