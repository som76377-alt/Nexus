package com.nexus.music.catalog

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/** Foreground work may pass queued background work, but never interrupts an active extraction. */
internal enum class ExtractorPriority {
    FOREGROUND,
    BACKGROUND
}

internal class ExtractorPriorityContext(
    val priority: ExtractorPriority
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ExtractorPriorityContext>
}

internal suspend fun <T> withExtractorPriority(
    priority: ExtractorPriority,
    block: suspend () -> T
): T = withContext(ExtractorPriorityContext(priority)) { block() }

/**
 * A cancellation-safe, priority-ordered exclusive gate.
 *
 * NewPipe has process-wide mutable state, so extraction must remain single-file. This gate orders
 * queued work by priority and FIFO within each priority; it does not run extractions concurrently.
 */
internal class PriorityExecutionGate {
    private val monitor = Any()
    private val waiters = PriorityQueue<Waiter>(
        compareBy<Waiter> { it.priority.ordinal }.thenBy { it.sequence }
    )
    private var held = false
    private var nextSequence = 0L

    suspend fun <T> withPermit(
        priority: ExtractorPriority,
        block: suspend () -> T
    ): T {
        val lease = acquire(priority)
        try {
            return block()
        } finally {
            lease.release()
        }
    }

    private suspend fun acquire(priority: ExtractorPriority): Lease =
        suspendCancellableCoroutine { continuation ->
            var immediate: Lease? = null
            var queued: Waiter? = null
            synchronized(monitor) {
                if (!held) {
                    held = true
                    immediate = Lease(this)
                } else {
                    queued = Waiter(priority, nextSequence++, continuation)
                    waiters.add(queued!!)
                }
            }

            queued?.let { waiter ->
                continuation.invokeOnCancellation {
                    synchronized(monitor) { waiters.remove(waiter) }
                }
            }
            immediate?.let { grant(continuation, it) }
        }

    private fun grant(continuation: CancellableContinuation<Lease>, lease: Lease) {
        // If cancellation wins after dequeue but before delivery, return the permit immediately.
        continuation.invokeOnCancellation { lease.release() }
        continuation.resume(lease)
    }

    private fun release() {
        val next = synchronized(monitor) {
            val waiter = waiters.poll()
            if (waiter == null) held = false
            waiter
        }
        next?.let { grant(it.continuation, Lease(this)) }
    }

    private class Waiter(
        val priority: ExtractorPriority,
        val sequence: Long,
        val continuation: CancellableContinuation<Lease>
    )

    private class Lease(private val owner: PriorityExecutionGate) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) owner.release()
        }
    }
}

/**
 * NewPipe's downloader is process-wide. Keep all global reconfiguration and extraction inside
 * one priority-aware gate. The same downloader is initialized only once until a diagnostic or
 * alternate transport deliberately switches it while holding the gate.
 */
internal object ExtractorRuntime {
    private val gate = PriorityExecutionGate()
    private var activeDownloader: Downloader? = null

    suspend fun <T> withDownloader(
        downloader: Downloader,
        block: () -> T
    ): T {
        val priority = currentCoroutineContext()[ExtractorPriorityContext]?.priority
            ?: ExtractorPriority.FOREGROUND
        return gate.withPermit(priority) {
            if (activeDownloader !== downloader) {
                NewPipe.init(downloader)
                activeDownloader = downloader
            }
            block()
        }
    }
}
