package com.nexus.music.catalog

import com.nexus.music.performance.NexusPerformance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Shares identical in-flight catalogue searches and keeps a small process-memory result cache.
 *
 * Shared work is independent of any single caller. Its own Job owns extractor admission, so
 * preemption cancels only the shared extraction child and never the Home loader that awaits it.
 */
internal class SearchRequestCoordinator<T>(
    private val requestScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
    private val promoteRequest: (Job) -> Boolean = { false }
) {
    private data class SearchKey(
        val normalizedQuery: String,
        val scope: YoutubeSearchScope
    )

    private data class CacheEntry<T>(
        val items: List<T>,
        val expiresAtMs: Long
    )

    private class InFlight<T>(
        val result: CompletableDeferred<List<T>>,
        var waiterCount: Int,
        @Volatile var priority: ExtractorWorkPriority,
        @Volatile var operationTimeoutMs: Long?,
        var worker: Job? = null
    )

    private sealed interface Decision<out T> {
        data class Cached<T>(val items: List<T>) : Decision<T>
        data class Pending<T>(
            val request: InFlight<T>,
            val shouldStart: Boolean,
            val workerToPromote: Job? = null
        ) : Decision<T>
    }

    private val lock = Any()
    private val cache = LinkedHashMap<SearchKey, CacheEntry<T>>(16, 0.75f, true)
    private val inFlight = mutableMapOf<SearchKey, InFlight<T>>()

    init {
        require(maxEntries > 0) { "Search cache capacity must be positive." }
        require(ttlMs > 0L) { "Search cache TTL must be positive." }
    }

    /** Compatibility overload for ordinary foreground searches. */
    suspend fun search(
        query: String,
        scope: YoutubeSearchScope,
        loader: suspend (String) -> List<T>
    ): List<T> = search(
        query = query,
        scope = scope,
        priority = ExtractorWorkPriority.FOREGROUND,
        loader = loader
    )

    suspend fun search(
        query: String,
        scope: YoutubeSearchScope,
        priority: ExtractorWorkPriority,
        operationTimeoutMs: Long? = null,
        loader: suspend (String) -> List<T>
    ): List<T> {
        val requestQuery = query.trim().replace(WHITESPACE, " ")
        require(requestQuery.isNotEmpty()) { "Search query must not be blank." }
        val key = SearchKey(requestQuery.lowercase(Locale.ROOT), scope)
        val traceContext = currentCoroutineContext()[NexusPerformance.TraceContext]

        val decision = synchronized(lock) {
            cachedValueLocked(key)?.let { return@synchronized Decision.Cached(it) }
            val active = inFlight[key]
            if (active != null) {
                active.waiterCount++
                val promote = if (priority.ordinal < active.priority.ordinal) {
                    active.priority = priority
                    active.operationTimeoutMs = null
                    active.worker
                } else null
                Decision.Pending(active, shouldStart = false, workerToPromote = promote)
            } else {
                val request = InFlight(
                    result = CompletableDeferred(),
                    waiterCount = 1,
                    priority = priority,
                    operationTimeoutMs = operationTimeoutMs
                )
                inFlight[key] = request
                Decision.Pending(request, shouldStart = true)
            }
        }

        when (decision) {
            is Decision.Cached -> return decision.items.toList()
            is Decision.Pending -> {
                val request = decision.request
                decision.workerToPromote?.let(promoteRequest)
                if (decision.shouldStart) {
                    val worker = requestScope.launch(start = CoroutineStart.LAZY) {
                        val workerJob = currentCoroutineContext()[Job]!!
                        try {
                            val context = ExtractorPriorityContext(request.priority, workerJob, request.operationTimeoutMs)
                            val result = withContext(
                                if (traceContext == null) context else context + traceContext
                            ) { loader(requestQuery) }.toList()
                            if (result.isNotEmpty()) {
                                synchronized(lock) {
                                    cache[key] = CacheEntry(result, nowMs() + ttlMs)
                                    while (cache.size > maxEntries) {
                                        val oldestKey = cache.entries.iterator().next().key
                                        cache.remove(oldestKey)
                                    }
                                }
                            }
                            request.result.complete(result)
                        } catch (error: Throwable) {
                            request.result.completeExceptionally(error)
                            if (error is Error) throw error
                        } finally {
                            synchronized(lock) {
                                if (inFlight[key] === request) inFlight.remove(key)
                            }
                        }
                    }
                    synchronized(lock) {
                        request.worker = worker
                        if (request.waiterCount == 0) worker.cancel()
                    }
                    worker.start()
                }

                try {
                    return request.result.await().toList()
                } finally {
                    val workerToCancel = synchronized(lock) {
                        request.waiterCount--
                        if (request.waiterCount == 0 && inFlight[key] === request) {
                            inFlight.remove(key)
                            request.worker
                        } else null
                    }
                    workerToCancel?.cancel()
                }
            }
        }
    }

    private fun cachedValueLocked(key: SearchKey): List<T>? {
        val entry = cache[key] ?: return null
        if (nowMs() >= entry.expiresAtMs) {
            cache.remove(key)
            return null
        }
        return entry.items
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 24
        const val DEFAULT_TTL_MS = 5 * 60_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val WHITESPACE = Regex("\\s+")
    }
}
