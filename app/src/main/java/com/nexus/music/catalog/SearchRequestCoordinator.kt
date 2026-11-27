package com.nexus.music.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Shares identical in-flight catalogue searches and keeps a small process-memory result cache.
 *
 * The work runs in an application-owned scope rather than the first caller's scope: cancelling
 * one screen/request therefore cannot cancel a search still awaited by another caller.
 */
internal class SearchRequestCoordinator<T>(
    private val requestScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND }
) {
    private data class SearchKey(
        val normalizedQuery: String,
        val scope: YoutubeSearchScope
    )

    private data class CacheEntry<T>(
        val items: List<T>,
        val expiresAtMs: Long
    )

    private sealed interface Decision<out T> {
        data class Cached<T>(val items: List<T>) : Decision<T>
        data class Pending<T>(
            val deferred: CompletableDeferred<List<T>>,
            val shouldStart: Boolean
        ) : Decision<T>
    }

    private val lock = Any()
    private val cache = LinkedHashMap<SearchKey, CacheEntry<T>>(16, 0.75f, true)
    private val inFlight = mutableMapOf<SearchKey, CompletableDeferred<List<T>>>()

    init {
        require(maxEntries > 0) { "Search cache capacity must be positive." }
        require(ttlMs > 0L) { "Search cache TTL must be positive." }
    }

    suspend fun search(
        query: String,
        scope: YoutubeSearchScope,
        loader: suspend (String) -> List<T>
    ): List<T> {
        val requestQuery = query.trim().replace(WHITESPACE, " ")
        require(requestQuery.isNotEmpty()) { "Search query must not be blank." }
        val key = SearchKey(requestQuery.lowercase(Locale.ROOT), scope)

        val decision = synchronized(lock) {
            cachedValueLocked(key)?.let { return@synchronized Decision.Cached(it) }
            val active = inFlight[key]
            if (active != null) {
                Decision.Pending(active, shouldStart = false)
            } else {
                val deferred = CompletableDeferred<List<T>>()
                inFlight[key] = deferred
                Decision.Pending(deferred, shouldStart = true)
            }
        }

        when (decision) {
            is Decision.Cached -> return decision.items.toList()
            is Decision.Pending -> {
                if (decision.shouldStart) {
                    requestScope.launch {
                        try {
                            val result = loader(requestQuery).toList()
                            synchronized(lock) {
                                cache[key] = CacheEntry(result, nowMs() + ttlMs)
                                while (cache.size > maxEntries) {
                                    val oldestKey = cache.entries.iterator().next().key
                                    cache.remove(oldestKey)
                                }
                            }
                            decision.deferred.complete(result)
                        } catch (error: Throwable) {
                            decision.deferred.completeExceptionally(error)
                            if (error is Error) throw error
                        } finally {
                            synchronized(lock) {
                                if (inFlight[key] === decision.deferred) {
                                    inFlight.remove(key)
                                }
                            }
                        }
                    }
                }
                return decision.deferred.await().toList()
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
