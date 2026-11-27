package com.nexus.music.ui

import com.nexus.music.catalog.HomeShelfSpec
import com.nexus.music.catalog.YoutubeSearchScope
import kotlin.random.Random

/**
 * Builds a varied discovery sequence for one Home session.
 * Three of every five shelves use the listener's recent/saved artists as a relevance signal;
 * the other two deliberately explore a genre, language, mood or discovery query without that bias.
 */
internal object HomeDiscoveryPlanner {
    const val PAGE_SIZE = 5
    private const val TASTE_SHELVES_PER_PAGE = 3

    fun createPlan(
        groups: List<HomeDiscoveryGroup>,
        recentArtists: List<String>,
        seed: Long
    ): List<HomeShelfSpec> {
        val categories = groups.flatMap { it.categories }.distinctBy { it.id }
        if (categories.isEmpty()) return emptyList()

        val orderedCategories = categories.shuffled(Random(seed))
        val artists = recentArtists.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(30)
            .toList()
            .shuffled(Random(seed xor 0x5DEECE66DL))

        return orderedCategories.mapIndexed { index, category ->
            val slotInPage = index % PAGE_SIZE
            val artist = if (artists.isNotEmpty() && slotInPage < TASTE_SHELVES_PER_PAGE) {
                artists[(index / PAGE_SIZE * TASTE_SHELVES_PER_PAGE + slotInPage) % artists.size]
            } else null
            val baseQuery = artist?.let { "$it ${category.query}" } ?: category.query
            val variants = if (category.scope == YoutubeSearchScope.EVERYTHING) {
                listOf("extended mix", "long version", "background listening", "full session")
            } else {
                listOf("official audio", "full song", "music video", "new tracks", "deep cuts")
            }
            val variant = variants[Math.floorMod(seed + index * 31L, variants.size.toLong()).toInt()]
            val query = "$baseQuery $variant"
            val title = artist?.let { "${category.label} with $it" } ?: category.label
            val queryKey = query.hashCode().toUInt().toString(16)
            HomeShelfSpec(
                id = "${category.id}-$queryKey",
                title = title,
                query = query,
                scope = category.scope
            )
        }
    }
}
