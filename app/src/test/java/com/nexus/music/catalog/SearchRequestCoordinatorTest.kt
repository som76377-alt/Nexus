package com.nexus.music.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SearchRequestCoordinatorTest {
    @Test
    fun concurrentIdenticalSearchesShareOneRequestAndPopulateCache() = runBlocking {
        val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = SearchRequestCoordinator<String>(requestScope)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<List<String>>()
        val calls = AtomicInteger()

        val first = async {
            coordinator.search("  Ambient   Jazz ", YoutubeSearchScope.MUSIC_SONGS) {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
            }
        }
        withTimeout(1_000L) { started.await() }
        val second = async {
            coordinator.search("ambient jazz", YoutubeSearchScope.MUSIC_SONGS) {
                calls.incrementAndGet()
                listOf("unexpected duplicate")
            }
        }
        yield() // Ensure the second caller has joined the in-flight request before it completes.

        release.complete(listOf("Track A", "Track B"))
        assertEquals(listOf("Track A", "Track B"), first.await())
        assertEquals(listOf("Track A", "Track B"), second.await())
        assertEquals(listOf("Track A", "Track B"), coordinator.search(
            "AMBIENT JAZZ",
            YoutubeSearchScope.MUSIC_SONGS
        ) {
            calls.incrementAndGet()
            emptyList()
        })
        assertEquals(1, calls.get())
    }

    @Test
    fun cancellingOneWaiterDoesNotCancelSharedProviderRequest() = runBlocking {
        val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = SearchRequestCoordinator<String>(requestScope)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<List<String>>()
        val calls = AtomicInteger()
        val loader: suspend (String) -> List<String> = {
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
        }

        val cancelledWaiter = async {
            coordinator.search("same query", YoutubeSearchScope.EVERYTHING, loader)
        }
        withTimeout(1_000L) { started.await() }
        val remainingWaiter = async {
            coordinator.search("same query", YoutubeSearchScope.EVERYTHING, loader)
        }

        cancelledWaiter.cancelAndJoin()
        release.complete(listOf("Still available"))
        assertEquals(listOf("Still available"), remainingWaiter.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun cancellingFinalWaiterCancelsProviderWorkAndAllowsRetry() = runBlocking {
        val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = SearchRequestCoordinator<String>(requestScope)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<List<String>>()
        val calls = AtomicInteger()

        val waiter = async {
            coordinator.search("cancel me", YoutubeSearchScope.EVERYTHING) {
                calls.incrementAndGet()
                started.complete(Unit)
                try {
                    neverCompletes.await()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        withTimeout(1_000L) { started.await() }
        waiter.cancelAndJoin()
        withTimeout(1_000L) { cancelled.await() }

        assertEquals(listOf("Retry succeeded"), coordinator.search(
            "cancel me",
            YoutubeSearchScope.EVERYTHING
        ) {
            calls.incrementAndGet()
            listOf("Retry succeeded")
        })
        assertEquals(2, calls.get())
    }

    @Test
    fun searchScopeIsPartOfTheCacheKey() = runBlocking {
        val coordinator = SearchRequestCoordinator<String>(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        val calls = AtomicInteger()

        val everything = coordinator.search("ambient", YoutubeSearchScope.EVERYTHING) {
            calls.incrementAndGet()
            listOf("Everything")
        }
        val music = coordinator.search("ambient", YoutubeSearchScope.MUSIC_SONGS) {
            calls.incrementAndGet()
            listOf("Music")
        }

        assertEquals(listOf("Everything"), everything)
        assertEquals(listOf("Music"), music)
        assertEquals(2, calls.get())
    }

    @Test
    fun failedSearchIsNotCachedAndCanBeRetried() = runBlocking {
        val coordinator = SearchRequestCoordinator<String>(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        var calls = 0

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                coordinator.search("retry me", YoutubeSearchScope.EVERYTHING) {
                    calls++
                    throw IllegalStateException("temporary failure")
                }
            }
        }
        assertEquals(listOf("Recovered"), coordinator.search(
            "retry me",
            YoutubeSearchScope.EVERYTHING
        ) {
            calls++
            listOf("Recovered")
        })
        assertEquals(2, calls)
    }

    @Test
    fun cacheEvictsLeastRecentlyUsedEntryAtCapacity() = runBlocking {
        val coordinator = SearchRequestCoordinator<String>(
            requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            maxEntries = 2,
            ttlMs = 10_000L,
            nowMs = { 0L }
        )
        val calls = mutableMapOf<String, Int>()
        suspend fun search(query: String): List<String> = coordinator.search(
            query,
            YoutubeSearchScope.EVERYTHING
        ) {
            calls[it] = (calls[it] ?: 0) + 1
            listOf(it)
        }

        search("one")
        search("two")
        search("one") // Refresh "one" as most recently used.
        search("three") // Evicts "two".
        search("two")
        assertEquals(2, calls["two"])
        assertEquals(1, calls["one"])
    }

    @Test
    fun cacheExpiresAtTheTtlBoundary() = runBlocking {
        var now = 0L
        val coordinator = SearchRequestCoordinator<String>(
            requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            maxEntries = 2,
            ttlMs = 100L,
            nowMs = { now }
        )
        var calls = 0

        suspend fun search(): List<String> = coordinator.search(
            "one",
            YoutubeSearchScope.EVERYTHING
        ) {
            calls++
            listOf("one")
        }

        search()
        assertEquals(1, calls)
        now = 100L
        search()
        assertEquals(2, calls)
    }
}
