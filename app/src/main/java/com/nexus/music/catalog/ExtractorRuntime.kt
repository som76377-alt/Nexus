package com.nexus.music.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal enum class ExtractorWorkPriority { FOREGROUND, DIAGNOSTIC, BACKGROUND }

internal class ExtractorPriorityContext(
    val priority: ExtractorWorkPriority,
    val ownerJob: Job?
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ExtractorPriorityContext>
}

private class ExtractorLeaseContext(
    var downloader: Downloader
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ExtractorLeaseContext>
}

/** Exclusive, priority-aware admission for NewPipe's process-wide mutable runtime. */
internal class PriorityAdmissionQueue {
    private data class Waiter(
        val priority: ExtractorWorkPriority,
        val ownerJob: Job?,
        val signal: CompletableDeferred<Unit> = CompletableDeferred(),
        var granted: Boolean = false
    )

    private val monitor = Any()
    private val waiters = mutableListOf<Waiter>()
    private var occupied = false
    private var activePriority = ExtractorWorkPriority.BACKGROUND
    private var activeJob: Job? = null

    internal val queuedCount: Int
        get() = synchronized(monitor) { waiters.size }

    suspend fun <T> withLease(
        priority: ExtractorWorkPriority,
        ownerJob: Job? = null,
        block: suspend () -> T
    ): T {
        acquire(priority, ownerJob ?: currentCoroutineContext()[Job])
        try {
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(priority: ExtractorWorkPriority, ownerJob: Job?) {
        val waiter = synchronized(monitor) {
            if (!occupied && waiters.isEmpty()) {
                occupied = true
                activePriority = priority
                activeJob = ownerJob
                return
            }
            Waiter(priority, ownerJob).also { candidate ->
                val insertion = waiters.indexOfFirst {
                    it.priority.ordinal > candidate.priority.ordinal
                }
                if (insertion < 0) waiters.add(candidate) else waiters.add(insertion, candidate)
            }
        }

        // Cancellation handlers can release the lease, so never cancel while holding the monitor.
        val preemptedJob = synchronized(monitor) {
            activeJob.takeIf { priority.ordinal < activePriority.ordinal }
        }
        preemptedJob?.cancel(
            CancellationException("NewPipe work preempted by ${priority.name.lowercase()} request")
        )

        try {
            waiter.signal.await()
        } catch (cancelled: CancellationException) {
            synchronized(monitor) {
                if (waiter.granted) grantNextLocked() else waiters.remove(waiter)
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

    /** Called with monitor held; ownership transfers before the next waiter is resumed. */
    private fun grantNextLocked() {
        val next = waiters.firstOrNull()
        if (next == null) {
            occupied = false
            activeJob = null
            activePriority = ExtractorWorkPriority.BACKGROUND
            return
        }
        waiters.removeAt(0)
        next.granted = true
        activePriority = next.priority
        activeJob = next.ownerJob
        occupied = true
        next.signal.complete(Unit)
    }
}

/** Identity-based downloader switching; callers must hold the exclusive admission lease. */
internal class DownloaderInitializationState<T : Any>(
    private val initialize: (T) -> Unit
) {
    private var active: T? = null

    fun ensureInitialized(downloader: T) {
        if (active !== downloader) {
            initialize(downloader)
            active = downloader
        }
    }
}

internal object ExtractorRuntime {
    internal val normalDownloader: OkHttpDownloader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpDownloader()
    }

    private val admission = PriorityAdmissionQueue()
    private val downloaderState = DownloaderInitializationState<Downloader> { NewPipe.init(it) }

    suspend fun <T> withDownloader(downloader: Downloader, block: () -> T): T {
        val lease = currentCoroutineContext()[ExtractorLeaseContext]
        if (lease != null) {
            if (lease.downloader !== downloader) {
                initializeDownloader(downloader)
                lease.downloader = downloader
            }
            return runExtraction(block)
        }

        val priorityContext = currentCoroutineContext()[ExtractorPriorityContext]
        val priority = priorityContext?.priority ?: ExtractorWorkPriority.FOREGROUND
        val ownerJob = priorityContext?.ownerJob ?: currentCoroutineContext()[Job]
        return withExclusiveDownloader(downloader, priority, ownerJob) {
            runExtraction(block)
        }
    }

    /** Holds the exclusive lease across a complete Provider Lab search-and-resolve operation. */
    suspend fun <T> withExclusiveDownloader(
        downloader: Downloader,
        priority: ExtractorWorkPriority,
        ownerJob: Job? = null,
        block: suspend () -> T
    ): T = admission.withLease(priority, ownerJob ?: currentCoroutineContext()[Job]) {
        initializeDownloader(downloader)
        withContext(ExtractorLeaseContext(downloader)) { block() }
    }

    private suspend fun initializeDownloader(downloader: Downloader) {
        runInterruptible(Dispatchers.IO) { downloaderState.ensureInitialized(downloader) }
    }

    private suspend fun <T> runExtraction(block: () -> T): T =
        runInterruptible(Dispatchers.IO) { block() }
}
