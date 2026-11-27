package com.nexus.music.playback

import com.nexus.music.catalog.ResolvedAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.util.LinkedHashMap

/**
 * Single-worker speculative audio resolver.
 *
 * Completed streams are process-memory only (12-entry LRU, 60-second freshness window).
 * Canonical source URLs are the identity; short-lived audio URLs are never persisted here.
 */
internal class AudioPrefetchCoordinator(
    private val scope: CoroutineScope,
    private val resolveInBackground: suspend (String) -> ResolvedAudio,
    private val promoteInFlight: (Job) -> Unit,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val freshnessMs: Long = DEFAULT_FRESHNESS_MS,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private data class Cached(val audio: ResolvedAudio, val storedAtMs: Long)
    private data class Active(val sourceUrl: String, val deferred: Deferred<ResolvedAudio>, var promoted: Boolean = false)

    private val lock = Any()
    private val completed = LinkedHashMap<String, Cached>(capacity, 0.75f, true)
    private val pending = ArrayDeque<String>()
    private var worker: Job? = null
    private var active: Active? = null

    init {
        require(capacity > 0)
        require(queueCapacity > 0)
        require(freshnessMs > 0L)
    }

    val cachedEntryCount: Int get() = synchronized(lock) { pruneExpiredLocked(); completed.size }
    val queuedCount: Int get() = synchronized(lock) { pending.size }
    val activeSourceUrl: String? get() = synchronized(lock) { active?.sourceUrl }
    internal fun containsCached(sourceUrl: String): Boolean = synchronized(lock) { pruneExpiredLocked(); completed.containsKey(sourceUrl) }

    /** Adds one speculative candidate. Duplicate URLs are coalesced; overflow drops oldest queued work. */
    fun enqueue(sourceUrl: String) {
        if (sourceUrl.isBlank()) return
        synchronized(lock) {
            pruneExpiredLocked()
            if (completed.containsKey(sourceUrl) ||
                active?.sourceUrl == sourceUrl ||
                pending.contains(sourceUrl)
            ) return
            while (pending.size >= queueCapacity) pending.removeFirst()
            pending.addLast(sourceUrl)
            startWorkerLocked()
        }
    }

    /**
     * Returns a recent prefetch result, promotes and joins a matching in-flight prefetch, or
     * cancels unrelated speculation before running the caller's foreground resolver.
     */
    suspend fun resolveForPlayback(
        sourceUrl: String,
        resolveForeground: suspend (String) -> ResolvedAudio
    ): ResolvedAudio {
        val matching: Active?
        val cached: ResolvedAudio?
        val unrelated: Job?
        synchronized(lock) {
            pruneExpiredLocked()
            cached = completed[sourceUrl]?.audio
            matching = active?.takeIf { it.sourceUrl == sourceUrl }
            matching?.promoted = true
            unrelated = active?.takeIf { it.sourceUrl != sourceUrl }?.deferred
            pending.clear()
            if (matching == null) completed.remove(sourceUrl)
        }

        if (cached != null) {
            unrelated?.cancel(CancellationException("Speculation cancelled by foreground playback"))
            return cached
        }

        if (matching != null) {
            promoteInFlight(matching.deferred)
            return try {
                matching.deferred.await()
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                resolveForeground(sourceUrl)
            } catch (_: Exception) {
                // A speculative failure must not turn an explicit Play tap into a failure.
                resolveForeground(sourceUrl)
            }
        }

        unrelated?.cancel(CancellationException("Unrelated speculation cancelled by foreground playback"))
        return resolveForeground(sourceUrl)
    }

    /** Cancels speculative work when a new urgent user action begins. */
    fun cancelSpeculation() {
        val running = synchronized(lock) {
            pending.clear()
            active?.takeIf { !it.promoted }?.deferred
        }
        running?.cancel(CancellationException("Speculative audio work superseded"))
    }

    suspend fun awaitIdle() {
        while (true) {
            val idle = synchronized(lock) {
                pending.isEmpty() && active == null && worker?.isActive != true
            }
            if (idle) return
            delay(1L)
        }
    }

    fun close() {
        val running = synchronized(lock) {
            pending.clear()
            active?.deferred
        }
        running?.cancel()
        synchronized(lock) { worker?.cancel(); worker = null }
    }

    private fun startWorkerLocked() {
        if (worker?.isActive == true) return
        worker = scope.launch(Dispatchers.IO) { drainQueue() }
    }

    private suspend fun drainQueue() {
        val thisWorker = currentCoroutineContext()[Job]
        try {
            while (currentCoroutineContext().isActive) {
                val sourceUrl = synchronized(lock) {
                    if (pending.isEmpty()) {
                        if (worker === thisWorker) worker = null
                        return
                    }
                    pending.removeFirst()
                }

                coroutineScope {
                    val task = async { resolveInBackground(sourceUrl) }
                    synchronized(lock) { active = Active(sourceUrl, task) }
                    try {
                        val audio = task.await()
                        synchronized(lock) {
                            completed[sourceUrl] = Cached(audio, nowMs())
                            while (completed.size > capacity) {
                                val eldest = completed.entries.iterator()
                                if (eldest.hasNext()) {
                                    eldest.next()
                                    eldest.remove()
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw cancelled
                    } catch (_: Exception) {
                        // A failed speculative resolution is deliberately not cached.
                    } finally {
                        synchronized(lock) {
                            if (active?.deferred === task) active = null
                        }
                    }
                }
            }
        } finally {
            synchronized(lock) {
                if (worker === thisWorker) worker = null
                if (active?.deferred?.isCancelled == true) active = null
                if (pending.isNotEmpty() && worker == null) startWorkerLocked()
            }
        }
    }

    private fun pruneExpiredLocked() {
        val cutoff = nowMs() - freshnessMs
        val iterator = completed.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.storedAtMs < cutoff) iterator.remove()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 12
        const val DEFAULT_QUEUE_CAPACITY = 12
        const val DEFAULT_FRESHNESS_MS = 60_000L
    }
}
