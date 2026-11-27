package com.nexus.music.catalog

import kotlin.random.Random

/** Pure local selection over cached tracks; it has no repository/network dependency. */
internal object HomeRerollSelector {
    @Suppress("UNUSED_PARAMETER")
    fun select(
        pool: List<CatalogTrack>,
        history: List<String>,
        count: Int,
        random: Random = Random.Default
    ): List<CatalogTrack> {
        if (count <= 0 || pool.isEmpty()) return emptyList()
        val uniquePool = pool.distinctBy { it.url }
        val seen = history.toHashSet()
        // The caller supplies a relevance-ranked pool. Preserve that ranking while still
        // exhausting unseen tracks before falling back to older recommendations.
        val unseen = uniquePool.filterNot { it.url in seen }
        val selected = unseen.take(count).toMutableList()
        if (selected.size < count) {
            val selectedUrls = selected.mapTo(hashSetOf()) { it.url }
            val historyOrder = history.withIndex().associate { it.value to it.index }
            val oldestShownFirst = uniquePool
                .filterNot { it.url in selectedUrls }
                .sortedBy { historyOrder[it.url] ?: Int.MIN_VALUE }
            selected += oldestShownFirst.take(count - selected.size)
        }
        return selected
    }
}

/** Merge a refresh without letting a transient empty result erase a useful stale shelf. */
internal fun refreshedHomeShelfSnapshot(
    previous: HomeShelfSnapshot?,
    fetchedAtEpochMs: Long,
    tracks: List<CatalogTrack>,
    poolLimit: Int,
    visibleLimit: Int,
    historyLimit: Int
): HomeShelfSnapshot {
    require(poolLimit > 0 && visibleLimit > 0 && historyLimit >= 0)
    val freshPool = tracks.distinctBy { it.url }.take(poolLimit)
    if (freshPool.isEmpty() && previous != null) {
        return previous.copy(fetchedAtEpochMs = fetchedAtEpochMs)
    }
    val selected = HomeRerollSelector.select(
        pool = freshPool,
        history = previous?.history.orEmpty(),
        count = visibleLimit
    )
    val selectedUrls = selected.map { it.url }
    val history = (
        previous?.history.orEmpty().filterNot { it in selectedUrls } + selectedUrls
    ).takeLast(historyLimit)
    return HomeShelfSnapshot(
        fetchedAtEpochMs = fetchedAtEpochMs,
        pool = freshPool,
        displayedTracks = selected,
        history = history
    )
}
