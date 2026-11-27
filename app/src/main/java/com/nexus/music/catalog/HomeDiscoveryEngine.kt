package com.nexus.music.catalog

import com.nexus.music.ui.HomeDiscoveryGroup
import kotlin.math.ln
import kotlin.random.Random

/**
 * On-device discovery planner. It starts with listener signals plus a small exploratory
 * seed set, then grows the plan from genuinely new artist names returned by catalogue searches.
 */
internal object HomeDiscoveryEngine {
    const val PAGE_SIZE = 5
    private const val INITIAL_PLAN_SIZE = 9
    private const val MAX_DYNAMIC_SHELVES = 40
    private const val ARTISTS_PER_EXPANSION = 4

    fun createInitialPlan(
        groups: List<HomeDiscoveryGroup>,
        recentTracks: List<CatalogTrack>,
        savedTracks: List<CatalogTrack>,
        memory: HomeDiscoveryMemory,
        seed: Long
    ): List<HomeShelfSpec> {
        val random = Random(seed)
        val artistSeeds = (savedTracks + recentTracks)
            .mapNotNull { track ->
                track.artist?.trim()?.takeIf { normalizeArtistName(it)?.let { key -> key !in memory.dislikedArtists } == true }
            }
            .distinctBy { normalizeArtistName(it) }
            .take(4)

        val tasteQueries = artistSeeds.flatMap { artist ->
            listOf(
                HomeShelfSpec(
                    id = "similar-${stableKey(artist)}",
                    title = "If you like $artist",
                    query = "artists similar to $artist music",
                    scope = YoutubeSearchScope.MUSIC_SONGS
                ),
                HomeShelfSpec(
                    id = "beyond-${stableKey(artist)}",
                    title = "Beyond $artist",
                    query = "songs like $artist by different artists",
                    scope = YoutubeSearchScope.MUSIC_SONGS
                )
            )
        }.shuffled(random)

        val categories = groups.flatMap { it.categories }
            .filterNot { category -> memory.dislikedArtists.any { it in category.query.lowercase() } }
            .shuffled(random)
        val exploration = categories.map { category ->
            HomeShelfSpec(
                id = "explore-${category.id}",
                title = "Explore ${category.label}",
                query = "${category.query} emerging artists",
                scope = category.scope
            )
        }

        val combined = mutableListOf<HomeShelfSpec>()
        var tasteIndex = 0
        var explorationIndex = 0
        while (combined.size < INITIAL_PLAN_SIZE &&
            (tasteIndex < tasteQueries.size || explorationIndex < exploration.size)
        ) {
            if (tasteIndex < tasteQueries.size && (combined.size % 5 < 3 || explorationIndex >= exploration.size)) {
                combined += tasteQueries[tasteIndex++]
            } else if (explorationIndex < exploration.size) {
                combined += exploration[explorationIndex++]
            } else {
                combined += tasteQueries[tasteIndex++]
            }
        }
        return combined.distinctBy { it.id }.take(INITIAL_PLAN_SIZE)
    }

    /** Turns search results into the next generation of artist shelves. */
    fun expandFromResults(
        results: List<CatalogTrack>,
        knownArtists: Set<String>,
        existingShelfIds: Set<String>,
        memory: HomeDiscoveryMemory,
        currentShelfCount: Int,
        seed: Long
    ): List<HomeShelfSpec> {
        val remaining = (MAX_DYNAMIC_SHELVES - currentShelfCount).coerceAtLeast(0)
        if (remaining == 0) return emptyList()
        val known = knownArtists.mapNotNull(::normalizeArtistName).toSet()
        val candidates = results.asSequence()
            .mapNotNull { it.artist?.trim()?.takeIf { name -> normalizeArtistName(name) != null } }
            .distinctBy(::normalizeArtistName)
            .filter { artist ->
                val key = normalizeArtistName(artist) ?: return@filter false
                key !in known && key !in memory.dislikedArtists
            }
            .toList()
            .shuffled(Random(seed))
            .take(minOf(ARTISTS_PER_EXPANSION, remaining))

        return candidates.mapNotNull { artist ->
            val id = "artist-${stableKey(artist)}"
            if (id in existingShelfIds) return@mapNotNull null
            HomeShelfSpec(
                id = id,
                title = "A new voice: $artist",
                query = "$artist official audio",
                scope = YoutubeSearchScope.MUSIC_SONGS
            )
        }
    }

    /** Novelty-first ranking with a light taste signal and a modest popularity tie-breaker. */
    fun rankTracks(
        tracks: List<CatalogTrack>,
        memory: HomeDiscoveryMemory,
        recentTracks: List<CatalogTrack>,
        savedTracks: List<CatalogTrack>,
        seed: Long
    ): List<CatalogTrack> {
        val recentWeights = recentTracks.mapNotNull { normalizeArtistName(it.artist) }
            .distinct().withIndex().associate { it.value to (12 - it.index.coerceAtMost(11)) }
        val savedArtists = savedTracks.mapNotNull { normalizeArtistName(it.artist) }.toSet()
        return tracks.asSequence()
            .distinctBy { it.url }
            .filterNot { track ->
                track.url in memory.dismissedTrackUrls ||
                    normalizeArtistName(track.artist)?.let { it in memory.dislikedArtists } == true
            }
            .sortedByDescending { track ->
                val artist = normalizeArtistName(track.artist)
                val affinity = artist?.let {
                    (memory.playedArtistWeights[it] ?: 0) +
                        (recentWeights[it] ?: 0) +
                        if (it in savedArtists) 18 else 0
                } ?: 0
                val novelty = if (track.url in memory.recommendedTrackUrls) -30 else 30
                val newArtistBonus = if (artist != null && affinity == 0) 9 else 0
                val popularity = ln((track.viewCount ?: 0L).coerceAtLeast(0L).toDouble() + 1.0) * 0.35
                val tieBreak = Random(seed xor track.url.hashCode().toLong()).nextDouble()
                novelty + affinity * 1.5 + newArtistBonus + popularity + tieBreak
            }
            .toList()
    }

    private fun stableKey(value: String): String =
        value.trim().lowercase().hashCode().toUInt().toString(16)
}
