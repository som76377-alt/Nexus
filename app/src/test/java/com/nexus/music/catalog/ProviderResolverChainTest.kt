package com.nexus.music.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import java.io.IOException

class ProviderResolverChainTest {
    @Test
    fun resolveNetworkFailureIsPenalizedAfterAnotherProviderSucceeds() = runBlocking {
        val health = InMemoryProviderHealthStore()
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
        val failed = health.get(StreamProvider.NEWPIPE, ProviderOperation.RESOLVE)
        assertEquals(1, failed.consecutiveFailures)
        assertEquals(StreamErrorClass.NETWORK, failed.lastErrorClass)
        assertEquals(true, failed.cooldownUntilEpochMs > 1_000L)
        assertEquals(0, health.get(StreamProvider.PIPED, ProviderOperation.RESOLVE).consecutiveFailures)
    }

    @Test
    fun searchFallsBackWhenFirstProviderReturnsNoResults() = runBlocking {
        val calls = mutableListOf<StreamProvider>()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, calls, search = { emptyList() }) { resolvedAudio() },
                FakeAdapter(StreamProvider.PIPED, calls, search = { listOf(catalogTrack()) }) { resolvedAudio() }
            ),
            healthStore = InMemoryProviderHealthStore(),
            providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED),
            hedgeDelayMs = 0L
        )

        val results = chain.search("ambient")

        assertEquals(listOf(catalogTrack()), results)
        assertEquals(listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED), calls)
    }

    @Test
    fun searchNetworkFailureIsPenalizedAfterAnotherProviderSucceeds() = runBlocking {
        val health = InMemoryProviderHealthStore()
        val calls = mutableListOf<StreamProvider>()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, calls, search = { throw IOException("offline") }) { resolvedAudio() },
                FakeAdapter(StreamProvider.PIPED, calls, search = { listOf(catalogTrack()) }) { resolvedAudio() }
            ),
            healthStore = health,
            providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED),
            hedgeDelayMs = 0L,
            nowEpochMs = { 1_000L }
        )

        val results = chain.search("ambient")

        assertEquals(listOf(catalogTrack()), results)
        assertEquals(listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED), calls)
        val failed = health.get(StreamProvider.NEWPIPE, ProviderOperation.SEARCH)
        assertEquals(1, failed.consecutiveFailures)
        assertEquals(StreamErrorClass.NETWORK, failed.lastErrorClass)
        assertEquals(true, failed.cooldownUntilEpochMs > 1_000L)
        assertEquals(0, health.get(StreamProvider.PIPED, ProviderOperation.SEARCH).consecutiveFailures)
    }

    @Test
    fun searchReturnsEmptyWhenEveryProviderHasNoMatches() = runBlocking {
        val calls = mutableListOf<StreamProvider>()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, calls, search = { emptyList() }) { resolvedAudio() },
                FakeAdapter(StreamProvider.PIPED, calls, search = { emptyList() }) { resolvedAudio() }
            ),
            healthStore = InMemoryProviderHealthStore(),
            providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED),
            hedgeDelayMs = 0L
        )

        assertEquals(emptyList<CatalogTrack>(), chain.search("no matching song"))
        assertEquals(listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED), calls)
    }

    @Test
    fun unsupportedCapabilityFallsBackWithoutPoisoningProviderHealth() = runBlocking {
        val health = InMemoryProviderHealthStore()
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
    fun contentUnavailableFallsBackWithoutOpeningProviderCircuit() = runBlocking {
        val health = InMemoryProviderHealthStore()
        val calls = mutableListOf<StreamProvider>()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, calls) {
                    throw ContentNotAvailableException("video unavailable")
                },
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
        assertEquals(0, health.get(StreamProvider.NEWPIPE).consecutiveFailures)
        assertEquals(0L, health.get(StreamProvider.NEWPIPE).cooldownUntilEpochMs)
    }

    @Test
    fun networkFailureIsPenalizedOnlyWhenPeerSucceeds() = runBlocking {
        val health = InMemoryProviderHealthStore()
        val calls = mutableListOf<StreamProvider>()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, calls) { throw IOException("network unavailable") },
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
        val failed = health.get(StreamProvider.NEWPIPE, ProviderOperation.RESOLVE)
        assertEquals(1, failed.consecutiveFailures)
        assertEquals(StreamErrorClass.NETWORK, failed.lastErrorClass)
        assertEquals(true, failed.cooldownUntilEpochMs > 1_000L)
        assertEquals(0, health.get(StreamProvider.PIPED, ProviderOperation.RESOLVE).consecutiveFailures)
    }

    @Test
    fun healthyProviderIsTriedBeforeProviderInCooldown() = runBlocking {
        val health = InMemoryProviderHealthStore().apply {
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
        val health = InMemoryProviderHealthStore()
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
        val health = InMemoryProviderHealthStore()
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
        val health = InMemoryProviderHealthStore()
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
        val health = InMemoryProviderHealthStore()
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

    @Test
    fun recoveryResolutionExcludesTheProviderThatProducedTheFailedUrl() = runBlocking {
        val calls = mutableListOf<StreamProvider>()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, calls) { resolvedAudio() },
                FakeAdapter(StreamProvider.PIPED, calls) { resolvedAudio().copy(audioUrl = "https://media.example/piped.m4a") },
                FakeAdapter(StreamProvider.INVIDIOUS, calls) { resolvedAudio() }
            ),
            healthStore = InMemoryProviderHealthStore(),
            providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED, StreamProvider.INVIDIOUS),
            hedgeDelayMs = 0L
        )

        val replacement = chain.resolveAudio(
            "https://youtube.com/watch?v=abcdefghijk",
            excludingProviders = setOf(StreamProvider.NEWPIPE)
        )

        assertEquals(listOf(StreamProvider.PIPED, StreamProvider.INVIDIOUS), calls)
        assertEquals(StreamProvider.PIPED, replacement.provider)
        assertEquals("https://media.example/piped.m4a", replacement.audioUrl)
    }


    @Test
    fun streamProbeValidationFailureFallsThroughToNextProvider() = runBlocking {
        val calls = mutableListOf<StreamProvider>()
        var validations = 0
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, calls) { resolvedAudio() },
                FakeAdapter(StreamProvider.PIPED, calls) {
                    resolvedAudio().copy(audioUrl = "https://media.example/piped.m4a")
                }
            ),
            healthStore = InMemoryProviderHealthStore(),
            providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED),
            hedgeDelayMs = 0L,
            validateResolvedAudio = { audio ->
                validations += 1
                if (validations == 1) throw IOException("stream probe rejected the first URL")
                audio
            }
        )

        val result = chain.resolveAudio("https://youtube.com/watch?v=abcdefghijk")

        assertEquals(listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED), calls)
        assertEquals(2, validations)
        assertEquals(StreamProvider.PIPED, result.provider)
        assertEquals("https://media.example/piped.m4a", result.audioUrl)
    }

    @Test
    fun repositoryCanSearchWithOneProviderAndResolveSameVideoWithAnother() = runBlocking {
        val track = catalogTrack()
        val calls = mutableListOf<StreamProvider>()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, calls, search = { listOf(track) }) {
                    throw IOException("NewPipe resolve unavailable")
                },
                FakeAdapter(StreamProvider.PIPED, calls, search = { emptyList() }) {
                    resolvedAudio().copy(
                        sourceUrl = track.url,
                        audioUrl = "https://media.example/piped.m4a"
                    )
                }
            ),
            healthStore = InMemoryProviderHealthStore(),
            providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED),
            hedgeDelayMs = 100L
        )
        val repository = YoutubeCatalogRepository(
            adapter = ResolverChainStreamProviderAdapter(chain)
        )

        val found = repository.search("ambient")
        val resolved = repository.resolveAudio(found.single().url)

        assertEquals(track, found.single())
        assertEquals(track.url, resolved.sourceUrl)
        assertEquals(StreamProvider.PIPED, resolved.provider)
        assertEquals(
            listOf(StreamProvider.NEWPIPE, StreamProvider.NEWPIPE, StreamProvider.PIPED),
            calls
        )
    }

    @Test
    fun excludingEveryProviderPreservesIllegalArgumentExceptionContract() {
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, mutableListOf()) { resolvedAudio() }
            ),
            healthStore = InMemoryProviderHealthStore(),
            providerOrder = listOf(StreamProvider.NEWPIPE)
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                chain.resolveAudio(
                    "https://youtube.com/watch?v=abcdefghijk",
                    excludingProviders = setOf(StreamProvider.NEWPIPE)
                )
            }
        }
    }


    @Test
    fun searchAndResolveHealthRecordsAreIndependent() = runBlocking {
        val health = InMemoryProviderHealthStore()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(
                    StreamProvider.NEWPIPE,
                    mutableListOf(),
                    search = { throw ProviderHttpException(503, "newpipe") }
                ) { throw ProviderHttpException(503, "newpipe") }
            ),
            healthStore = health,
            providerOrder = listOf(StreamProvider.NEWPIPE),
            hedgeDelayMs = 0L,
            nowEpochMs = { 1_000L }
        )

        assertThrows(IOException::class.java) { runBlocking { chain.search("ambient") } }
        assertEquals(1, health.get(StreamProvider.NEWPIPE, ProviderOperation.SEARCH).consecutiveFailures)
        assertEquals(0, health.get(StreamProvider.NEWPIPE, ProviderOperation.RESOLVE).consecutiveFailures)

        assertThrows(IOException::class.java) {
            runBlocking { chain.resolveAudio("https://youtube.com/watch?v=abcdefghijk") }
        }
        assertEquals(1, health.get(StreamProvider.NEWPIPE, ProviderOperation.SEARCH).consecutiveFailures)
        assertEquals(1, health.get(StreamProvider.NEWPIPE, ProviderOperation.RESOLVE).consecutiveFailures)
    }

    @Test
    fun allNetworkAttemptsFailWithoutPeerSuccessAndDoNotChangeHealth() = runBlocking {
        val health = InMemoryProviderHealthStore()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, mutableListOf()) { throw IOException("offline") },
                FakeAdapter(StreamProvider.PIPED, mutableListOf()) { throw IOException("offline") }
            ),
            healthStore = health,
            providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED),
            hedgeDelayMs = 0L,
            nowEpochMs = { 1_000L }
        )

        assertThrows(IOException::class.java) {
            runBlocking { chain.resolveAudio("https://youtube.com/watch?v=abcdefghijk") }
        }

        assertEquals(0, health.get(StreamProvider.NEWPIPE, ProviderOperation.RESOLVE).consecutiveFailures)
        assertEquals(0, health.get(StreamProvider.PIPED, ProviderOperation.RESOLVE).consecutiveFailures)
    }

    @Test
    fun searchFourHundredStatusesAreRecordedImmediately() = runBlocking {
        listOf(400, 401, 403, 404, 405, 410).forEach { status ->
            val health = InMemoryProviderHealthStore()
            val chain = ProviderResolverChain(
                adapters = listOf(
                    FakeAdapter(
                        StreamProvider.NEWPIPE,
                        mutableListOf(),
                        search = { throw ProviderHttpException(status, "newpipe") }
                    ) { resolvedAudio() }
                ),
                healthStore = health,
                providerOrder = listOf(StreamProvider.NEWPIPE),
                hedgeDelayMs = 0L,
                nowEpochMs = { 1_000L }
            )

            assertThrows(IOException::class.java) { runBlocking { chain.search("ambient") } }
            assertEquals(
                "status=$status",
                1,
                health.get(StreamProvider.NEWPIPE, ProviderOperation.SEARCH).consecutiveFailures
            )
        }
    }

    @Test
    fun serverErrorsAndRateLimitsAreRecordedEvenWhenEveryAttemptFails() = runBlocking {
        listOf(408, 429, 500, 503).forEach { status ->
            val health = InMemoryProviderHealthStore()
            val chain = ProviderResolverChain(
                adapters = listOf(
                    FakeAdapter(StreamProvider.PIPED, mutableListOf()) {
                        throw ProviderHttpException(status, "piped")
                    }
                ),
                healthStore = health,
                providerOrder = listOf(StreamProvider.PIPED),
                hedgeDelayMs = 0L,
                nowEpochMs = { 1_000L }
            )

            assertThrows(IOException::class.java) {
                runBlocking { chain.resolveAudio("https://youtube.com/watch?v=abcdefghijk") }
            }
            assertEquals(
                "status=$status",
                1,
                health.get(StreamProvider.PIPED, ProviderOperation.RESOLVE).consecutiveFailures
            )
        }
    }

    @Test
    fun resolveAccessDeniedIsOnlyPenalizedWhenAnotherProviderSucceeds() = runBlocking {
        val health = InMemoryProviderHealthStore()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, mutableListOf()) {
                    throw ProviderHttpException(403, "newpipe")
                },
                FakeAdapter(StreamProvider.PIPED, mutableListOf()) { resolvedAudio() }
            ),
            healthStore = health,
            providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED),
            hedgeDelayMs = 0L,
            nowEpochMs = { 1_000L }
        )

        chain.resolveAudio("https://youtube.com/watch?v=abcdefghijk")

        val failed = health.get(StreamProvider.NEWPIPE, ProviderOperation.RESOLVE)
        assertEquals(1, failed.consecutiveFailures)
        assertEquals(StreamErrorClass.ACCESS_BLOCKED, failed.lastErrorClass)
        assertEquals(true, failed.cooldownUntilEpochMs > 1_000L)
        assertEquals(0, health.get(StreamProvider.PIPED, ProviderOperation.RESOLVE).consecutiveFailures)
    }

    @Test
    fun resolveAccessDeniedWithNoPeerSuccessDoesNotChangeHealth() = runBlocking {
        val health = InMemoryProviderHealthStore()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, mutableListOf()) {
                    throw ProviderHttpException(403, "newpipe")
                }
            ),
            healthStore = health,
            providerOrder = listOf(StreamProvider.NEWPIPE),
            hedgeDelayMs = 0L
        )

        assertThrows(IOException::class.java) {
            runBlocking { chain.resolveAudio("https://youtube.com/watch?v=abcdefghijk") }
        }
        assertEquals(0, health.get(StreamProvider.NEWPIPE, ProviderOperation.RESOLVE).consecutiveFailures)
    }

    @Test
    fun streamProbe403And410ArePenalizedOnlyAfterPeerSuccess() = runBlocking {
        listOf(403, 410).forEach { status ->
            val health = InMemoryProviderHealthStore()
            val chain = ProviderResolverChain(
                adapters = listOf(
                    FakeAdapter(StreamProvider.NEWPIPE, mutableListOf()) {
                        throw ProviderStreamProbeException(status)
                    },
                    FakeAdapter(StreamProvider.PIPED, mutableListOf()) { resolvedAudio() }
                ),
                healthStore = health,
                providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED),
                hedgeDelayMs = 0L,
                nowEpochMs = { 1_000L }
            )

            chain.resolveAudio("https://youtube.com/watch?v=abcdefghijk")

            val failed = health.get(StreamProvider.NEWPIPE, ProviderOperation.RESOLVE)
            assertEquals("status=$status", 1, failed.consecutiveFailures)
            assertEquals(true, failed.cooldownUntilEpochMs > 1_000L)
            assertEquals(0, health.get(StreamProvider.PIPED, ProviderOperation.RESOLVE).consecutiveFailures)
        }
    }

    @Test
    fun malformedProviderResponseIsRecordedAsProviderFailure() = runBlocking {
        val health = InMemoryProviderHealthStore()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.INVIDIOUS, mutableListOf()) {
                    throw ProviderResponseException("invalid JSON response")
                }
            ),
            healthStore = health,
            providerOrder = listOf(StreamProvider.INVIDIOUS),
            hedgeDelayMs = 0L,
            nowEpochMs = { 1_000L }
        )

        assertThrows(IOException::class.java) {
            runBlocking { chain.resolveAudio("https://youtube.com/watch?v=abcdefghijk") }
        }
        assertEquals(1, health.get(StreamProvider.INVIDIOUS, ProviderOperation.RESOLVE).consecutiveFailures)
        assertEquals(StreamErrorClass.UNKNOWN, health.get(StreamProvider.INVIDIOUS, ProviderOperation.RESOLVE).lastErrorClass)
    }

    @Test
    fun contentSpecificFailureNeverChangesProviderHealth() = runBlocking {
        val health = InMemoryProviderHealthStore()
        val chain = ProviderResolverChain(
            adapters = listOf(
                FakeAdapter(StreamProvider.NEWPIPE, mutableListOf()) {
                    throw ContentNotAvailableException("video unavailable")
                }
            ),
            healthStore = health,
            providerOrder = listOf(StreamProvider.NEWPIPE),
            hedgeDelayMs = 0L
        )

        assertThrows(Exception::class.java) {
            runBlocking { chain.resolveAudio("https://youtube.com/watch?v=abcdefghijk") }
        }
        assertEquals(0, health.get(StreamProvider.NEWPIPE, ProviderOperation.RESOLVE).consecutiveFailures)
    }

    @Test
    fun inMemoryHealthStoreKeepsOperationsAndInstancesIndependent() {
        val lab = InMemoryProviderHealthStore()
        val production = InMemoryProviderHealthStore()
        lab.recordFailure(
            provider = StreamProvider.PIPED,
            errorClass = StreamErrorClass.TRANSIENT,
            atEpochMs = 1_000L,
            cooldownUntilEpochMs = 2_000L,
            operation = ProviderOperation.SEARCH
        )

        assertEquals(1, lab.get(StreamProvider.PIPED, ProviderOperation.SEARCH).consecutiveFailures)
        assertEquals(0, lab.get(StreamProvider.PIPED, ProviderOperation.RESOLVE).consecutiveFailures)
        assertEquals(0, production.get(StreamProvider.PIPED, ProviderOperation.SEARCH).consecutiveFailures)
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

}
