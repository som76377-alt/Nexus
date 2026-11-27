package com.nexus.music.catalog

import kotlin.random.Random

/** Pure local selection over cached tracks; it has no repository/network dependency. */
internal object HomeRerollSelector {
    fun select(
        pool: List<CatalogTrack>,
        history: List<String>,
        count: Int,
        random: Random = Random.Default
    ): List<CatalogTrack> {
        if (count <= 0 || pool.isEmpty()) return emptyList()
        val uniquePool = pool.distinctBy { it.url }
        val seen = history.toHashSet()
        val unseen = uniquePool.filterNot { it.url in seen }.shuffled(random)
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
