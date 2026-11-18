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
            val waiter = Waiter(priority, nextSequence++, continuation)
            // One handler owns both queue removal and cleanup if a permit was already assigned.
            continuation.invokeOnCancellation {
                val assignedLease = synchronized(monitor) {
                    if (waiters.remove(waiter)) null else waiter.lease
                }
                assignedLease?.release()
            }

            var granted: Lease? = null
            synchronized(monitor) {
                if (continuation.isActive) {
                    if (!held) {
                        held = true
                        granted = Lease(this).also { waiter.lease = it }
                    } else {
                        waiter.sequence = nextSequence++
                        waiters.add(waiter)
                    }
                }
            }
            granted?.let { continuation.resume(it) }
        }

    private fun release() {
        val next = synchronized(monitor) {
            val waiter = waiters.poll()
            if (waiter == null) {
                held = false
                null
            } else {
                Lease(this).also { waiter.lease = it }
            }
        }
        if (next != null) {
            // The waiter and its lease are paired under the monitor before cancellation can run.
            val continuation = synchronized(monitor) {
                // The lease owner is the gate; the selected waiter is the one carrying this lease.
                // No other waiter can receive this lease because it is stored on exactly one waiter.
                waiters.firstOrNull { it.lease === next }?.continuation
            }
            continuation?.resume(next)
        }
    }

    private class Waiter(
        val priority: ExtractorPriority,
        var sequence: Long,
        val continuation: CancellableContinuation<Lease>
    ) {
        var lease: Lease? = null
    }

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
