package com.nexus.music.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import com.nexus.music.performance.NexusPerformance
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal enum class ExtractorWorkPriority { FOREGROUND, DIAGNOSTIC, BACKGROUND }

internal class ExtractorPriorityContext(
    val priority: ExtractorWorkPriority,
    val ownerJob: Job?,
    val operationTimeoutMs: Long? = null
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
        var priority: ExtractorWorkPriority,
        val ownerJob: Job?,
        val signal: CompletableDeferred<Unit> = CompletableDeferred(),
        var granted: Boolean = false
    )

    private val monitor = Any()
    private val waiters = mutableListOf<Waiter>()
    private val promotedJobs = mutableSetOf<Job>()
    private var occupied = false
    private var activePriority = ExtractorWorkPriority.BACKGROUND
    private var activeJob: Job? = null

    internal val activePriorityForTest: ExtractorWorkPriority
        get() = synchronized(monitor) { activePriority }

    internal val queuedCount: Int
        get() = synchronized(monitor) { waiters.size }

    /** Promote one matching speculative owner without cancelling or duplicating its extraction. */
    fun promote(ownerJob: Job): Boolean = synchronized(monitor) {
        if (activeJob === ownerJob) {
            activePriority = ExtractorWorkPriority.FOREGROUND
            return@synchronized true
        }
        val waiter = waiters.firstOrNull { it.ownerJob === ownerJob }
        if (waiter != null) {
            waiter.priority = ExtractorWorkPriority.FOREGROUND
            waiters.remove(waiter)
            val insertion = waiters.indexOfFirst {
                it.priority.ordinal > ExtractorWorkPriority.FOREGROUND.ordinal
            }
            if (insertion < 0) waiters.add(waiter) else waiters.add(insertion, waiter)
            return@synchronized true
        }
        // The user can tap between enqueue and the background coroutine entering this gate.
        // Remember promotion so its eventual admission is FOREGROUND rather than losing the tap.
        promotedJobs.add(ownerJob)
        ownerJob.invokeOnCompletion { synchronized(monitor) { promotedJobs.remove(ownerJob) } }
        true
    }

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
            val effectivePriority = if (ownerJob != null && promotedJobs.remove(ownerJob)) {
                ExtractorWorkPriority.FOREGROUND
            } else {
                priority
            }
            if (!occupied && waiters.isEmpty()) {
                occupied = true
                activePriority = effectivePriority
                activeJob = ownerJob
                return
            }
            Waiter(effectivePriority, ownerJob).also { candidate ->
                val insertion = waiters.indexOfFirst {
                    it.priority.ordinal > candidate.priority.ordinal
                }
                if (insertion < 0) waiters.add(candidate) else waiters.add(insertion, candidate)
            }
        }

        // Cancellation handlers can release the lease, so never cancel while holding the monitor.
        val preemptedJob = synchronized(monitor) {
            activeJob.takeIf { waiter.priority.ordinal < activePriority.ordinal }
        }
        preemptedJob?.cancel(
            CancellationException("NewPipe work preempted by ${priority.name.lowercase()} request")
        )

        try {
            waiter.signal.await()
        } catch (cancelled: CancellationException) {
            val next = synchronized(monitor) {
                if (waiter.granted) grantNextLocked() else {
                    waiters.remove(waiter)
                    null
                }
            }
            next?.signal?.complete(Unit)
            throw cancelled
        }
    }

    private fun release() {
        val next = synchronized(monitor) {
            check(occupied) { "NewPipe coordinator lease released without ownership." }
            grantNextLocked()
        }
        // Resume outside the monitor so an Unconfined continuation cannot run user work under it.
        next?.signal?.complete(Unit)
    }

    /** Called with monitor held; ownership transfers before the next waiter is resumed. */
    private fun grantNextLocked(): Waiter? {
        val next = waiters.firstOrNull()
        if (next == null) {
            occupied = false
            activeJob = null
            activePriority = ExtractorWorkPriority.BACKGROUND
            return null
        }
        waiters.removeAt(0)
        next.granted = true
        activePriority = next.priority
        activeJob = next.ownerJob
        occupied = true
        return next
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
        val trace = currentCoroutineContext()[NexusPerformance.TraceContext]?.trace
        return withExclusiveDownloader(downloader, priority, ownerJob, trace) {
            val timeoutMs = priorityContext?.operationTimeoutMs
            if (timeoutMs != null) withTimeout(timeoutMs) { runExtraction(block) }
            else runExtraction(block)
        }
    }

    /** Holds the exclusive lease across the complete Provider Lab diagnostic run. */
    suspend fun <T> withExclusiveDownloader(
        downloader: Downloader,
        priority: ExtractorWorkPriority,
        ownerJob: Job? = null,
        trace: NexusPerformance.Trace? = currentCoroutineContext()[NexusPerformance.TraceContext]?.trace,
        block: suspend () -> T
    ): T {
        val queuedAt = if (NexusPerformance.isEnabled()) NexusPerformance.nowMs() else 0L
        return admission.withLease(priority, ownerJob ?: currentCoroutineContext()[Job]) {
            val acquiredAt = if (NexusPerformance.isEnabled()) NexusPerformance.nowMs() else 0L
            if (NexusPerformance.isEnabled()) {
                val detail = "queue_wait_ms=" + (acquiredAt - queuedAt) + " priority=" + priority
                if (trace != null) NexusPerformance.event(trace, "newpipe_lease_acquired", detail)
                else NexusPerformance.logEvent("newpipe_lease_acquired", detail)
            }
            try {
                initializeDownloader(downloader)
                withContext(ExtractorLeaseContext(downloader)) { block() }
            } finally {
                if (NexusPerformance.isEnabled()) {
                    val detail = "lease_hold_ms=" + (NexusPerformance.nowMs() - acquiredAt) + " priority=" + priority
                    if (trace != null) NexusPerformance.event(trace, "newpipe_lease_released", detail)
                    else NexusPerformance.logEvent("newpipe_lease_released", detail)
                }
            }
        }
    }

    /** Called when the user taps the exact track currently being prefetched. */
    internal fun promote(ownerJob: Job): Boolean = admission.promote(ownerJob)

    private suspend fun initializeDownloader(downloader: Downloader) {
        runInterruptible(Dispatchers.IO) { downloaderState.ensureInitialized(downloader) }
    }

    private suspend fun <T> runExtraction(block: () -> T): T =
        runInterruptible(Dispatchers.IO) { block() }
}
