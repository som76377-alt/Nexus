package com.nexus.music.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Admission class for work that must use NewPipe's process-wide mutable runtime. */
internal enum class ExtractorWorkPriority {
    FOREGROUND,
    DIAGNOSTIC,
    BACKGROUND
}

/** Carries an explicit lane through repositories, resolver children and provider adapters. */
internal class ExtractorPriorityContext(
    val priority: ExtractorWorkPriority
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ExtractorPriorityContext>
}

private class ExtractorLeaseContext(
    val downloader: Downloader
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ExtractorLeaseContext>
}

/**
 * NewPipe's Downloader and service localization are process-wide mutable state. Keep extraction
 * exclusive until the complete NewPipe pipeline is proven concurrency-safe. Foreground waiters
 * are admitted before queued diagnostic/background work; same-lane waiters remain FIFO.
 */
internal object ExtractorRuntime {
    private data class Waiter(
        val priority: ExtractorWorkPriority,
        val signal: CompletableDeferred<Unit> = CompletableDeferred(),
        var granted: Boolean = false
    )

    private val monitor = Any()
    private val waiters = mutableListOf<Waiter>()
    private var occupied = false
    private var activeDownloader: Downloader? = null

    suspend fun <T> withDownloader(
        downloader: Downloader,
        block: () -> T
    ): T {
        val lease = currentCoroutineContext()[ExtractorLeaseContext]
        if (lease != null && lease.downloader === downloader) return block()
        val priority = currentCoroutineContext()[ExtractorPriorityContext]?.priority
            ?: ExtractorWorkPriority.FOREGROUND
        return withExclusiveDownloader(downloader, priority) { block() }
    }

    /** Holds the exclusive NewPipe lease across a complete diagnostic operation. */
    suspend fun <T> withExclusiveDownloader(
        downloader: Downloader,
        priority: ExtractorWorkPriority,
        block: suspend () -> T
    ): T {
        val inherited = currentCoroutineContext()[ExtractorLeaseContext]
        check(inherited == null) { "A NewPipe exclusive lease is already active in this coroutine." }
        acquire(priority)
        try {
            initializeDownloader(downloader)
            return withContext(ExtractorLeaseContext(downloader)) { block() }
        } finally {
            release()
        }
    }

    private suspend fun acquire(priority: ExtractorWorkPriority) {
        val waiter = synchronized(monitor) {
            if (!occupied && waiters.isEmpty()) {
                occupied = true
                return
            }
            Waiter(priority).also { candidate ->
                val insertion = waiters.indexOfFirst {
                    it.priority.ordinal > candidate.priority.ordinal
                }
                if (insertion < 0) waiters.add(candidate) else waiters.add(insertion, candidate)
            }
        }

        try {
            waiter.signal.await()
        } catch (cancelled: CancellationException) {
            synchronized(monitor) {
                if (waiter.granted) {
                    grantNextLocked()
                } else {
                    waiters.remove(waiter)
                }
            }
            throw cancelled
        }
    }

    private fun release() {
        synchronized(monitor) {
            check(occupied) { "NewPipe coordinator lease released without ownership." }
            grantNextLocked()
        }
    }

    /** Called with monitor held; transfers ownership before waking the next waiter. */
    private fun grantNextLocked() {
        val next = waiters.firstOrNull()
        if (next == null) {
            occupied = false
            return
        }
        waiters.removeAt(0)
        next.granted = true
        occupied = true
        next.signal.complete(Unit)
    }

    private suspend fun initializeDownloader(downloader: Downloader) {
        runInterruptible(Dispatchers.IO) {
            synchronized(monitor) {
                if (activeDownloader !== downloader) {
                    NewPipe.init(downloader)
                    activeDownloader = downloader
                }
            }
        }
    }

    /** Runs a synchronous extractor call on IO and interrupts it when its coroutine is cancelled. */
    suspend fun <T> runExtraction(block: () -> T): T =
        runInterruptible(Dispatchers.IO) { block() }
}
