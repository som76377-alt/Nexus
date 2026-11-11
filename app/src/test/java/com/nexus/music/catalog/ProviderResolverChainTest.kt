package com.nexus.music.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ProviderResolverChainTest {
    @Test
    fun resolveFallsBackAndRecordsProviderHealth() = runBlocking {
        val health = InMemoryHealthStore()
        val calls = mutableListOf<StreamProvider>()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, calls) { throw IOException("offline") },
                FakeAdapter(StreamProvider.PIPED, calls) { resolvedAudio() }
            ),
            healthStore = health,
            providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED),
            hedgeDelayMs = 0L,
            nowEpochMs = { 1_000L }
        )

        val result = chain.resolveAudio("https://youtube.com/watch?v=abcdefghijk")

        assertEquals("https://media.example/audio.m4a", result.audioUrl)
        assertEquals(listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED), calls)
        assertEquals(1, health.get(StreamProvider.NEWPIPE).consecutiveFailures)
        assertEquals(StreamErrorClass.TRANSIENT, health.get(StreamProvider.NEWPIPE).lastErrorClass)
        assertEquals(0, health.get(StreamProvider.PIPED).consecutiveFailures)
    }

    @Test
    fun searchFallsBackWhenFirstProviderReturnsNoResults() = runBlocking {
        val calls = mutableListOf<StreamProvider>()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, calls, search = { emptyList() }) { resolvedAudio() },
                FakeAdapter(StreamProvider.PIPED, calls, search = { listOf(catalogTrack()) }) { resolvedAudio() }
            ),
            healthStore = InMemoryHealthStore(),
            providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED),
            hedgeDelayMs = 0L
        )

        val results = chain.search("ambient")

        assertEquals(listOf(catalogTrack()), results)
        assertEquals(listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED), calls)
    }

    @Test
    fun searchReturnsEmptyWhenEveryProviderHasNoMatches() = runBlocking {
        val calls = mutableListOf<StreamProvider>()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, calls, search = { emptyList() }) { resolvedAudio() },
                FakeAdapter(StreamProvider.PIPED, calls, search = { emptyList() }) { resolvedAudio() }
            ),
            healthStore = InMemoryHealthStore(),
            providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED),
            hedgeDelayMs = 0L
        )

        assertEquals(emptyList<CatalogTrack>(), chain.search("no matching song"))
        assertEquals(listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED), calls)
    }

    @Test
    fun unsupportedCapabilityFallsBackWithoutPoisoningProviderHealth() = runBlocking {
        val health = InMemoryHealthStore()
        val calls = mutableListOf<StreamProvider>()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.INVIDIOUS, calls) {
                    throw ProviderCapabilityException("music-songs filter unsupported")
                },
                FakeAdapter(StreamProvider.PIPED, calls) { resolvedAudio() }
            ),
            healthStore = health,
            providerOrder = listOf(StreamProvider.INVIDIOUS, StreamProvider.PIPED),
            hedgeDelayMs = 0L,
            nowEpochMs = { 1_000L }
        )

        val result = chain.resolveAudio("https://youtube.com/watch?v=abcdefghijk")

        assertEquals("https://media.example/audio.m4a", result.audioUrl)
        assertEquals(listOf(StreamProvider.INVIDIOUS, StreamProvider.PIPED), calls)
        assertEquals(0, health.get(StreamProvider.INVIDIOUS).consecutiveFailures)
        assertEquals(0L, health.get(StreamProvider.INVIDIOUS).cooldownUntilEpochMs)
    }

    @Test
    fun healthyProviderIsTriedBeforeProviderInCooldown() = runBlocking {
        val health = InMemoryHealthStore().apply {
            recordFailure(StreamProvider.NEWPIPE, StreamErrorClass.TRANSIENT, 900L, 5_000L)
        }
        val calls = mutableListOf<StreamProvider>()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, calls) { resolvedAudio() },
                FakeAdapter(StreamProvider.PIPED, calls) { resolvedAudio() }
            ),
            healthStore = health,
            providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED),
            hedgeDelayMs = 100L,
            nowEpochMs = { 1_000L }
        )

        chain.resolveAudio("https://youtube.com/watch?v=abcdefghijk")

        assertEquals(listOf(StreamProvider.PIPED), calls)
    }

    @Test
    fun invalidSourceIsNotRetriedOnOtherProviders() = runBlocking {
        val health = InMemoryHealthStore()
        val calls = mutableListOf<StreamProvider>()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, calls) { throw IllegalArgumentException("bad source") },
                FakeAdapter(StreamProvider.PIPED, calls) { resolvedAudio() }
            ),
            healthStore = health,
            providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED),
            hedgeDelayMs = 100L
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { chain.resolveAudio("not-a-youtube-url") }
        }
        assertEquals(listOf(StreamProvider.NEWPIPE), calls)
        assertEquals(0, health.get(StreamProvider.NEWPIPE).consecutiveFailures)
    }

    @Test
    fun attemptLimitBoundsProviderCalls() = runBlocking {
        val health = InMemoryHealthStore()
        val calls = mutableListOf<StreamProvider>()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, calls) { throw IOException("one") },
                FakeAdapter(StreamProvider.PIPED, calls) { throw IOException("two") },
                FakeAdapter(StreamProvider.INVIDIOUS, calls) { resolvedAudio() }
            ),
            healthStore = health,
            providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED, StreamProvider.INVIDIOUS),
            maxAttempts = 2,
            hedgeDelayMs = 0L
        )

        assertThrows(IOException::class.java) {
            runBlocking { chain.resolveAudio("https://youtube.com/watch?v=abcdefghijk") }
        }
        assertEquals(2, calls.size)
    }

    @Test
    fun callerCancellationStopsProviderWorkWithoutRecordingFailure() = runBlocking {
        val health = InMemoryHealthStore()
        val calls = mutableListOf<StreamProvider>()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, calls) {
                    started.complete(Unit)
                    try {
                        delay(5_000L)
                        resolvedAudio()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            ),
            healthStore = health,
            providerOrder = listOf(StreamProvider.NEWPIPE),
            hedgeDelayMs = 0L
        )

        val request = async { chain.resolveAudio("https://youtube.com/watch?v=abcdefghijk") }
        withTimeout(1_000L) { started.await() }
        request.cancelAndJoin()
        withTimeout(1_000L) { cancelled.await() }

        assertEquals(listOf(StreamProvider.NEWPIPE), calls)
        assertEquals(0, health.get(StreamProvider.NEWPIPE).consecutiveFailures)
    }

    @Test
    fun slowPreferredProviderIsHedgedAndLosingRequestIsCancelled() = runBlocking {
        val health = InMemoryHealthStore()
        val calls = mutableListOf<StreamProvider>()
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, calls) {
                    firstStarted.complete(Unit)
                    try {
                        delay(5_000L)
                        resolvedAudio()
                    } finally {
                        firstCancelled.complete(Unit)
                    }
                },
                FakeAdapter(StreamProvider.PIPED, calls) {
                    firstStarted.await()
                    resolvedAudio()
                }
            ),
            healthStore = health,
            providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED),
            hedgeDelayMs = 20L
        )

        val result = chain.resolveAudio("https://youtube.com/watch?v=abcdefghijk")

        assertEquals("https://media.example/audio.m4a", result.audioUrl)
        assertEquals(true, firstCancelled.isCompleted)
        assertEquals(0, health.get(StreamProvider.NEWPIPE).consecutiveFailures)
        assertEquals(0, health.get(StreamProvider.PIPED).consecutiveFailures)
    }

    private fun catalogTrack() = CatalogTrack(
        url = "https://youtube.com/watch?v=abcdefghijk",
        title = "Track",
        artist = "Artist",
        thumbnailUrl = null,
        durationSeconds = 60L,
        viewCount = 10L
    )

    private fun resolvedAudio() = ResolvedAudio(
        sourceUrl = "https://youtube.com/watch?v=abcdefghijk",
        title = "Track",
        artist = "Artist",
        thumbnailUrl = null,
        durationSeconds = 60L,
        audioUrl = "https://media.example/audio.m4a",
        averageBitrate = 128_000,
        mimeType = "audio/mp4"
    )

    private class FakeAdapter(
        override val provider: StreamProvider,
        private val calls: MutableList<StreamProvider>,
        private val search: suspend () -> List<CatalogTrack> = { throw IOException("not used") },
        private val resolve: suspend () -> ResolvedAudio
    ) : StreamProviderAdapter {
        override suspend fun search(query: String, scope: YoutubeSearchScope): List<CatalogTrack> {
            calls += provider
            return search()
        }

        override suspend fun resolveAudio(videoUrl: String): ResolvedAudio {
            calls += provider
            return resolve()
        }
    }

    private class InMemoryHealthStore : ProviderHealthStore {
        private val values = mutableMapOf<StreamProvider, ProviderHealth>()

        override fun get(provider: StreamProvider): ProviderHealth = values[provider] ?: ProviderHealth()

        override fun recordSuccess(provider: StreamProvider, atEpochMs: Long) {
            values[provider] = ProviderHealth(lastSuccessEpochMs = atEpochMs)
        }

        override fun recordFailure(
            provider: StreamProvider,
            errorClass: StreamErrorClass,
            atEpochMs: Long,
            cooldownUntilEpochMs: Long
        ) {
            val current = get(provider)
            values[provider] = current.copy(
                consecutiveFailures = current.consecutiveFailures + 1,
                cooldownUntilEpochMs = cooldownUntilEpochMs,
                lastErrorClass = errorClass
            )
        }

        override fun clear(provider: StreamProvider) {
            values.remove(provider)
        }
    }
}
