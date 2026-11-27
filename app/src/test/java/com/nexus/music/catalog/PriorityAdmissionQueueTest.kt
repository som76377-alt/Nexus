package com.nexus.music.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class PriorityAdmissionQueueTest {
    @Test
    fun downloaderIsInitializedOncePerIdentityAndSwitchesOnlyWhenRequired() {
        val initialized = mutableListOf<Any>()
        val state = DownloaderInitializationState<Any> { initialized += it }
        val normal = Any()
        val diagnostic = Any()

        state.ensureInitialized(normal)
        state.ensureInitialized(normal)
        state.ensureInitialized(diagnostic)
        state.ensureInitialized(diagnostic)
        state.ensureInitialized(normal)

        assertEquals(listOf(normal, diagnostic, normal), initialized)
    }

    @Test
    fun foregroundPreemptsActiveBackgroundAndRunsBeforeQueuedBackground() = runBlocking {
        val queue = PriorityAdmissionQueue()
        val backgroundStarted = CompletableDeferred<Unit>()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val activeBackground = launch {
            queue.withLease(ExtractorWorkPriority.BACKGROUND) {
                backgroundStarted.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        backgroundStarted.await()

        val queuedBackground = launch {
            queue.withLease(ExtractorWorkPriority.BACKGROUND) {
                order += "background"
            }
        }
        awaitQueued(queue, 1)

        val foreground = launch {
            queue.withLease(ExtractorWorkPriority.FOREGROUND) {
                order += "foreground"
            }
        }

        withTimeout(2_000) {
            foreground.join()
            queuedBackground.join()
        }
        assertTrue(activeBackground.isCancelled)
        assertEquals(listOf("foreground", "background"), order)
    }

    @Test
    fun foregroundJumpsAheadOfQueuedDiagnosticWork(): Unit = runBlocking {
        val queue = PriorityAdmissionQueue()
        val activeStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = Collections.synchronizedList(mutableListOf<String>())

        val activeBackground = launch {
            queue.withLease(ExtractorWorkPriority.BACKGROUND) {
                activeStarted.complete(Unit)
                release.await()
            }
        }
        activeStarted.await()

        val diagnostic = launch {
            queue.withLease(ExtractorWorkPriority.DIAGNOSTIC) { order += "diagnostic" }
        }
        awaitQueued(queue, 1)

        val foreground = launch {
            queue.withLease(ExtractorWorkPriority.FOREGROUND) { order += "foreground" }
        }
        withTimeout(2_000) {
            foreground.join()
            diagnostic.join()
        }
        assertTrue(activeBackground.isCancelled)
        assertEquals(listOf("foreground", "diagnostic"), order)
        release.complete(Unit)
    }

    @Test
    fun cancellingQueuedWaiterDoesNotConsumeTheNextLease() = runBlocking {
        val queue = PriorityAdmissionQueue()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            queue.withLease(ExtractorWorkPriority.FOREGROUND) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val cancelledWaiter = launch {
            queue.withLease(ExtractorWorkPriority.BACKGROUND) { error("Cancelled waiter entered") }
        }
        awaitQueued(queue, 1)
        cancelledWaiter.cancelAndJoin()
        assertEquals(0, queue.queuedCount)

        val nextEntered = CompletableDeferred<Unit>()
        val next = launch {
            queue.withLease(ExtractorWorkPriority.FOREGROUND) {
                nextEntered.complete(Unit)
            }
        }
        awaitQueued(queue, 1)
        release.complete(Unit)
        withTimeout(2_000) { nextEntered.await() }
        holder.join()
        next.join()
        assertTrue(next.isCompleted)
    }

    @Test
    fun samePriorityWaitersRemainFifoAndLeasesNeverOverlap() = runBlocking {
        val queue = PriorityAdmissionQueue()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()

        val holder = launch {
            queue.withLease(ExtractorWorkPriority.FOREGROUND) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val first = launch {
            queue.withLease(ExtractorWorkPriority.BACKGROUND) {
                val count = active.incrementAndGet()
                maximumActive.updateAndGet { old -> maxOf(old, count) }
                order += 1
                delay(10)
                active.decrementAndGet()
            }
        }
        awaitQueued(queue, 1)
        val second = launch {
            queue.withLease(ExtractorWorkPriority.BACKGROUND) {
                val count = active.incrementAndGet()
                maximumActive.updateAndGet { old -> maxOf(old, count) }
                order += 2
                delay(10)
                active.decrementAndGet()
            }
        }
        awaitQueued(queue, 2)

        release.complete(Unit)
        withTimeout(2_000) {
            holder.join()
            first.join()
            second.join()
        }
        assertEquals(listOf(1, 2), order)
        assertEquals(1, maximumActive.get())
    }

    private suspend fun awaitQueued(queue: PriorityAdmissionQueue, count: Int) {
        withTimeout(2_000) {
            while (queue.queuedCount != count) delay(1)
        }
    }
}
