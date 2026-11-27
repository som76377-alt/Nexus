package com.nexus.music.playback

import com.nexus.music.catalog.ResolvedAudio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AudioPrefetchCoordinatorTest {
    @Test
    fun duplicateRequestsShareOneBackgroundResolution() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val coordinator = AudioPrefetchCoordinator(
            scope = this,
            resolveInBackground = {
                calls.incrementAndGet()
                gate.await()
                audio(it)
            },
            promoteInFlight = {}
        )

        coordinator.enqueue("track-a")
        awaitCondition { coordinator.activeSourceUrl == "track-a" }
        coordinator.enqueue("track-a")
        assertEquals(0, coordinator.queuedCount)
        gate.complete(Unit)
        coordinator.awaitIdle()

        assertEquals(1, calls.get())
        assertEquals(1, coordinator.cachedEntryCount)
    }

    @Test
    fun tappingMatchingInFlightTrackPromotesAndJoinsIt() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val promoted = CompletableDeferred<kotlinx.coroutines.Job>()
        val coordinator = AudioPrefetchCoordinator(
            scope = this,
            resolveInBackground = {
                calls.incrementAndGet()
                gate.await()
                audio(it)
            },
            promoteInFlight = { promoted.complete(it) }
        )

        coordinator.enqueue("track-a")
        awaitCondition { coordinator.activeSourceUrl == "track-a" }
        val playback = async {
            coordinator.resolveForPlayback("track-a") {
                error("Foreground resolver must not duplicate a matching prefetch")
            }
        }
        withTimeout(2_000) { promoted.await() }
        gate.complete(Unit)
        assertEquals("track-a", withTimeout(2_000) { playback.await() }.sourceUrl)
        coordinator.awaitIdle()

        assertEquals(1, calls.get())
        assertEquals(1, coordinator.cachedEntryCount)
    }

    @Test
    fun backgroundingDoesNotCancelAPlayRequestThatPromotedThePrefetch() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val promoted = CompletableDeferred<Unit>()
        val coordinator = AudioPrefetchCoordinator(
            scope = this,
            resolveInBackground = {
                gate.await()
                audio(it)
            },
            promoteInFlight = { promoted.complete(Unit) }
        )

        coordinator.enqueue("track-a")
        awaitCondition { coordinator.activeSourceUrl == "track-a" }
        val playback = async { coordinator.resolveForPlayback("track-a") { error("must join") } }
        promoted.await()
        coordinator.cancelSpeculation()
        gate.complete(Unit)

        assertEquals("track-a", withTimeout(2_000) { playback.await() }.sourceUrl)
        coordinator.awaitIdle()
    }

    @Test
    fun unrelatedTapCancelsSpeculationBeforeForegroundResolution() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val coordinator = AudioPrefetchCoordinator(
            scope = this,
            resolveInBackground = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
            promoteInFlight = {}
        )
        coordinator.enqueue("speculative")
        awaitCondition { coordinator.activeSourceUrl == "speculative" }

        val result = coordinator.resolveForPlayback("tapped") { audio(it) }
        withTimeout(2_000) { cancelled.await() }
        coordinator.awaitIdle()

        assertEquals("tapped", result.sourceUrl)
        assertEquals(0, coordinator.queuedCount)
        assertEquals(null, coordinator.activeSourceUrl)
    }

    @Test
    fun completedAudioMemoryIsBoundedToTwelveEntries() = runBlocking {
        val coordinator = AudioPrefetchCoordinator(
            scope = this,
            resolveInBackground = { audio(it) },
            promoteInFlight = {}
        )

        repeat(13) { index ->
            coordinator.enqueue("track-$index")
            coordinator.awaitIdle()
        }

        assertEquals(AudioPrefetchCoordinator.DEFAULT_CAPACITY, coordinator.cachedEntryCount)
        assertFalse(coordinator.containsCached("track-0"))
        assertTrue(coordinator.containsCached("track-12"))
    }

    @Test
    fun pendingQueueNeverExceedsItsTwelveEntryBound() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val coordinator = AudioPrefetchCoordinator(
            scope = this,
            resolveInBackground = {
                gate.await()
                audio(it)
            },
            promoteInFlight = {}
        )

        coordinator.enqueue("active")
        awaitCondition { coordinator.activeSourceUrl == "active" }
        repeat(20) { coordinator.enqueue("queued-$it") }

        assertEquals(AudioPrefetchCoordinator.DEFAULT_QUEUE_CAPACITY, coordinator.queuedCount)
        gate.complete(Unit)
        coordinator.awaitIdle()
    }

    @Test
    fun failedMatchingPrefetchFallsBackToForegroundResolution() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val foregroundCalls = AtomicInteger()
        val coordinator = AudioPrefetchCoordinator(
            scope = this,
            resolveInBackground = {
                gate.await()
                error("speculative extraction failed")
            },
            promoteInFlight = {}
        )

        coordinator.enqueue("track-a")
        awaitCondition { coordinator.activeSourceUrl == "track-a" }
        val playback = async {
            coordinator.resolveForPlayback("track-a") {
                foregroundCalls.incrementAndGet()
                audio(it)
            }
        }
        gate.complete(Unit)

        assertEquals("track-a", withTimeout(2_000) { playback.await() }.sourceUrl)
        coordinator.awaitIdle()
        assertEquals(1, foregroundCalls.get())
    }

    @Test
    fun usingCompletedPrefetchKeepsItInTheLruCache() = runBlocking {
        val coordinator = AudioPrefetchCoordinator(
            scope = this,
            resolveInBackground = { audio(it) },
            promoteInFlight = {}
        )
        coordinator.enqueue("track-a")
        coordinator.awaitIdle()

        val result = coordinator.resolveForPlayback("track-a") {
            error("Completed prefetch should be reused")
        }

        assertEquals("track-a", result.sourceUrl)
        assertTrue(coordinator.containsCached("track-a"))
    }

    @Test
    fun completedPrefetchWithExpiredSignedUrlFallsBackToForegroundResolution() = runBlocking {
        var now = 1_700_000_000_000L
        val foregroundCalls = AtomicInteger()
        val coordinator = AudioPrefetchCoordinator(
            scope = this,
            resolveInBackground = { source ->
                audio(
                    source,
                    "https://audio.example/$source?expire=${(now / 1_000L) + (AudioCachePolicy.STALE_EARLY_MS / 1_000L)}"
                )
            },
            promoteInFlight = {},
            nowMs = { now }
        )

        coordinator.enqueue("expired-track")
        coordinator.awaitIdle()

        val result = coordinator.resolveForPlayback("expired-track") {
            foregroundCalls.incrementAndGet()
            audio(it, "https://audio.example/$it?expire=${(now / 1_000L) + 3_600L}")
        }

        assertEquals("expired-track", result.sourceUrl)
        assertEquals(1, foregroundCalls.get())
        assertFalse(coordinator.containsCached("expired-track"))
    }

    @Test
    fun missingExpiryMetadataUsesOriginalResolutionTimeForFallbackTtl() = runBlocking {
        var now = 1_700_000_000_000L
        val foregroundCalls = AtomicInteger()
        val coordinator = AudioPrefetchCoordinator(
            scope = this,
            resolveInBackground = { audio(it) },
            promoteInFlight = {},
            freshnessMs = 30 * 60_000L,
            nowMs = { now }
        )

        coordinator.enqueue("unknown-expiry")
        coordinator.awaitIdle()
        now += 20 * 60_000L

        val result = coordinator.resolveForPlayback("unknown-expiry") {
            foregroundCalls.incrementAndGet()
            audio(it)
        }

        assertEquals("unknown-expiry", result.sourceUrl)
        assertEquals(1, foregroundCalls.get())
        assertFalse(coordinator.containsCached("unknown-expiry"))
    }

    @Test
    fun onlyOneSpeculativeResolutionRunsAtATime() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val coordinator = AudioPrefetchCoordinator(
            scope = this,
            resolveInBackground = {
                val count = active.incrementAndGet()
                maximum.updateAndGet { old -> maxOf(old, count) }
                delay(5)
                active.decrementAndGet()
                audio(it)
            },
            promoteInFlight = {}
        )

        repeat(6) { coordinator.enqueue("track-$it") }
        coordinator.awaitIdle()

        assertEquals(1, maximum.get())
        assertEquals(6, coordinator.cachedEntryCount)
    }

    @Test
    fun failedPrefetchIsNotCachedAndDoesNotBlockNextItem() = runBlocking {
        val coordinator = AudioPrefetchCoordinator(
            scope = this,
            resolveInBackground = { if (it == "bad") error("offline") else audio(it) },
            promoteInFlight = {}
        )

        coordinator.enqueue("bad")
        coordinator.enqueue("good")
        coordinator.awaitIdle()

        assertEquals(1, coordinator.cachedEntryCount)
        assertTrue(coordinator.containsCached("good"))
        assertFalse(coordinator.containsCached("bad"))
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) delay(1)
        }
    }

    private fun audio(url: String, audioUrl: String = "https://audio.example/$url") = ResolvedAudio(
        sourceUrl = url,
        title = url,
        artist = null,
        thumbnailUrl = null,
        durationSeconds = null,
        audioUrl = audioUrl,
        averageBitrate = 128_000,
        mimeType = "audio/webm"
    )
}
