package com.nexus.music.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/** Provider-owned continuation token. Tokens are opaque and must never cross providers. */
data class SearchCursor(
    val provider: StreamProvider,
    internal val token: Any
)

/** One page from one source. An empty items list may still have another continuation page. */
data class SearchPage(
    val items: List<CatalogTrack>,
    val nextCursor: SearchCursor?,
    val hasMore: Boolean
)

/** Common contract for NewPipe, Piped, and Invidious search sources. */
interface SearchSource {
    val provider: StreamProvider

    suspend fun search(
        query: String,
        scope: YoutubeSearchScope,
        cursor: SearchCursor?
    ): SearchPage
}

data class SearchSnapshot(
    val items: List<CatalogTrack> = emptyList(),
    val isLoadingFirstPage: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val completedSources: Set<StreamProvider> = emptySet(),
    val failedSources: Set<StreamProvider> = emptySet(),
    val sourceCount: Int = 0
) {
    val allSourcesFailed: Boolean
        get() = sourceCount > 0 &&
            completedSources.size == sourceCount &&
            failedSources.size == sourceCount
}

/**
 * Isolated state and work for one query. Results publish as each source finishes, append-only,
 * with a single video-ID set shared across all sources and pages.
 */
class SearchSession internal constructor(
    val query: String,
    val searchScope: YoutubeSearchScope,
    private val parentScope: CoroutineScope,
    sources: List<SearchSource>,
    dispatcher: CoroutineDispatcher,
    private val maxConcurrentRequests: Int,
    private val sourceTimeoutMs: Long,
    private val roundDeadlineMs: Long,
    private val maxItems: Int,
    private val maxPagesPerSource: Int,
    private val maxConsecutiveNoNewPages: Int
) {
    private data class SourceProgress(
        val cursor: SearchCursor? = null,
        val hasMore: Boolean = false,
        val pagesLoaded: Int = 0,
        val consecutiveNoNewPages: Int = 0,
        val exhausted: Boolean = false
    )

    private val sourceList = sources.toList()
    private val sourcesByProvider = sourceList.associateBy { it.provider }
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(parentScope.coroutineContext + sessionJob + dispatcher)
    private val requestSemaphore = Semaphore(maxConcurrentRequests)
    private val stateMutex = Mutex()
    private val loadMoreMutex = Mutex()
    private val seenVideoIds = mutableSetOf<String>()
    private val sourceProgress = sourceList.associate { it.provider to SourceProgress() }.toMutableMap()
    private val mutableState = MutableStateFlow(SearchSnapshot(sourceCount = sourceList.size))
    private var firstPageJob: Job? = null

    val state: StateFlow<SearchSnapshot> = mutableState.asStateFlow()

    init {
        require(query.isNotBlank()) { "Search query must not be blank." }
        require(sourceList.isNotEmpty()) { "At least one search source is required." }
        require(sourcesByProvider.size == sourceList.size) { "Search sources must have unique providers." }
        require(maxConcurrentRequests > 0) { "Search concurrency must be positive." }
        require(sourceTimeoutMs > 0L && roundDeadlineMs > 0L) { "Search deadlines must be positive." }
        require(maxItems > 0 && maxPagesPerSource > 0 && maxConsecutiveNoNewPages > 0) {
            "Search result and pagination limits must be positive."
        }
    }

    @Synchronized
    fun start(): Job {
        firstPageJob?.let { return it }
        firstPageJob = sessionScope.launch { fetchFirstPages() }
        return firstPageJob!!
    }

    fun cancel() {
        sessionJob.cancel()
    }

    suspend fun loadMore() {
        val initialJob = firstPageJob ?: return
        initialJob.join()
        currentCoroutineContext().ensureActive()

        loadMoreMutex.withLock {
            val candidates = stateMutex.withLock {
                if (mutableState.value.isLoadingFirstPage || mutableState.value.items.size >= maxItems) {
                    emptyList()
                } else {
                    sourceList.filter { source ->
                        sourceProgress[source.provider]?.let { it.hasMore && it.cursor != null } == true
                    }
                }
            }
            if (candidates.isEmpty()) return

            stateMutex.withLock {
                mutableState.value = mutableState.value.copy(isLoadingMore = true)
            }
            val completedThisRound = ConcurrentHashMap.newKeySet<StreamProvider>()

            try {
                withTimeout(roundDeadlineMs) {
                    coroutineScope {
                        candidates.map { source ->
                            launch {
                                requestSemaphore.withPermit {
                                    val cursor = stateMutex.withLock {
                                        sourceProgress[source.provider]?.cursor
                                    }
                                    if (cursor == null || cursor.provider != source.provider) {
                                        markSourceFailed(source.provider)
                                        completedThisRound.add(source.provider)
                                        return@launch
                                    }
                                    try {
                                        val page = withTimeout(sourceTimeoutMs) {
                                            source.search(query, searchScope, cursor)
                                        }
                                        currentCoroutineContext().ensureActive()
                                        applyPage(source, page)
                                        completedThisRound.add(source.provider)
                                    } catch (error: TimeoutCancellationException) {
                                        currentCoroutineContext().ensureActive()
                                        markSourceFailed(source.provider)
                                        completedThisRound.add(source.provider)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        markSourceFailed(source.provider)
                                        completedThisRound.add(source.provider)
                                    }
                                }
                            }
                        }.joinAll()
                    }
                }
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                candidates.filterNot { it.provider in completedThisRound }
                    .forEach { markSourceFailed(it.provider) }
            } finally {
                stateMutex.withLock {
                    mutableState.value = mutableState.value.copy(
                        isLoadingMore = false,
                        hasMore = sourceProgress.values.any { it.hasMore }
                    )
                }
            }
        }
    }

    private suspend fun fetchFirstPages() {
        try {
            coroutineScope {
                sourceList.map { source ->
                    launch {
                        requestSemaphore.withPermit {
                            try {
                                val page = withTimeout(sourceTimeoutMs) {
                                    source.search(query, searchScope, null)
                                }
                                currentCoroutineContext().ensureActive()
                                applyPage(source, page)
                            } catch (error: TimeoutCancellationException) {
                                currentCoroutineContext().ensureActive()
                                markSourceFailed(source.provider)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                markSourceFailed(source.provider)
                            }
                        }
                    }
                }.joinAll()
            }
            stateMutex.withLock {
                mutableState.value = mutableState.value.copy(
                    isLoadingFirstPage = false,
                    hasMore = sourceProgress.values.any { it.hasMore }
                )
            }
        } catch (error: CancellationException) {
            throw error
        }
    }

    private suspend fun applyPage(source: SearchSource, page: SearchPage) {
        if (page.nextCursor != null && page.nextCursor.provider != source.provider) {
            throw IllegalArgumentException("A search cursor cannot move between providers.")
        }

        stateMutex.withLock {
            val previous = sourceProgress[source.provider] ?: SourceProgress(exhausted = true)
            val pageNumber = previous.pagesLoaded + 1
            val current = mutableState.value
            val newItems = mutableListOf<CatalogTrack>()

            page.items.forEach { track ->
                val videoId = track.videoId ?: return@forEach
                if (videoId !in seenVideoIds &&
                    current.items.size + newItems.size < maxItems
                ) {
                    seenVideoIds.add(videoId)
                    newItems += track
                }
            }

            val noNewPages = if (newItems.isEmpty()) {
                previous.consecutiveNoNewPages + 1
            } else {
                0
            }
            val canContinue = page.hasMore &&
                page.nextCursor != null &&
                pageNumber < maxPagesPerSource &&
                noNewPages < maxConsecutiveNoNewPages &&
                current.items.size + newItems.size < maxItems

            sourceProgress[source.provider] = SourceProgress(
                cursor = page.nextCursor.takeIf { canContinue },
                hasMore = canContinue,
                pagesLoaded = pageNumber,
                consecutiveNoNewPages = noNewPages,
                exhausted = !canContinue
            )
            mutableState.value = current.copy(
                items = current.items + newItems,
                hasMore = sourceProgress.values.any { it.hasMore },
                completedSources = current.completedSources + source.provider,
                failedSources = current.failedSources - source.provider
            )
        }
    }

    private suspend fun markSourceFailed(provider: StreamProvider) {
        stateMutex.withLock {
            val previous = sourceProgress[provider] ?: SourceProgress()
            sourceProgress[provider] = previous.copy(cursor = null, hasMore = false, exhausted = true)
            val current = mutableState.value
            mutableState.value = current.copy(
                hasMore = sourceProgress.values.any { it.hasMore },
                completedSources = current.completedSources + provider,
                failedSources = current.failedSources + provider
            )
        }
    }
}

/** Owns the active query session; starting a new query cancels the old session first. */
class SearchSessionManager(
    private val parentScope: CoroutineScope,
    private val sources: List<SearchSource>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxConcurrentRequests: Int = DEFAULT_MAX_CONCURRENT_REQUESTS,
    private val sourceTimeoutMs: Long = DEFAULT_SOURCE_TIMEOUT_MS,
    private val roundDeadlineMs: Long = DEFAULT_ROUND_DEADLINE_MS,
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
    private val maxPagesPerSource: Int = DEFAULT_MAX_PAGES_PER_SOURCE,
    private val maxConsecutiveNoNewPages: Int = DEFAULT_MAX_CONSECUTIVE_NO_NEW_PAGES
) {
    private var activeSession: SearchSession? = null

    @Synchronized
    fun start(query: String, scope: YoutubeSearchScope): SearchSession {
        activeSession?.cancel()
        return SearchSession(
            query = query.trim(),
            searchScope = scope,
            parentScope = parentScope,
            sources = sources,
            dispatcher = dispatcher,
            maxConcurrentRequests = maxConcurrentRequests,
            sourceTimeoutMs = sourceTimeoutMs,
            roundDeadlineMs = roundDeadlineMs,
            maxItems = maxItems,
            maxPagesPerSource = maxPagesPerSource,
            maxConsecutiveNoNewPages = maxConsecutiveNoNewPages
        ).also { session ->
            activeSession = session
            session.start()
        }
    }

    @Synchronized
    fun cancelActive() {
        activeSession?.cancel()
        activeSession = null
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_REQUESTS = 2
        const val DEFAULT_SOURCE_TIMEOUT_MS = 12_000L
        const val DEFAULT_ROUND_DEADLINE_MS = 8_000L
        const val DEFAULT_MAX_ITEMS = 150
        const val DEFAULT_MAX_PAGES_PER_SOURCE = 3
        const val DEFAULT_MAX_CONSECUTIVE_NO_NEW_PAGES = 2
    }
}
