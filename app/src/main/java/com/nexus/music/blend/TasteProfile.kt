package com.nexus.music.blend

import com.nexus.music.catalog.CatalogTrack
import com.nexus.music.library.PlayHistoryEvent
import kotlin.math.pow

data class ArtistTaste(
    val artist: String,
    val score: Double
)

data class TasteProfile(
    val generatedAtMs: Long,
    val artists: List<ArtistTaste>
)

/** Aggregated artist-only profile. Raw track history is never part of the shared model. */
object TasteProfileComputer {
    const val HALF_LIFE_DAYS = 30.0
    private const val DAY_MS = 86_400_000.0

    fun compute(events: List<PlayHistoryEvent>, nowMs: Long): TasteProfile {
        val scores = linkedMapOf<String, Double>()
        events.forEach { event ->
            val artist = event.track.artist?.trim()?.takeIf(String::isNotBlank) ?: return@forEach
            val age = (nowMs - event.playedAtMs).coerceAtLeast(0L).toDouble()
            val days = age / DAY_MS
            val weight = 0.5.pow(days / HALF_LIFE_DAYS)
            scores[artist] = (scores[artist] ?: 0.0) + weight
        }
        return TasteProfile(
            generatedAtMs = nowMs,
            artists = scores.entries
                .map { ArtistTaste(it.key, it.value) }
                .sortedWith(compareByDescending<ArtistTaste> { it.score }.thenBy { it.artist })
                .take(100)
        )
    }
}

/** Creates a 50-track list: shared favourites first, alternating favourites, then related picks. */
object BlendGenerator {
    fun generate(
        first: TasteProfile,
        second: TasteProfile,
        tracksByArtist: Map<String, List<CatalogTrack>>,
        relatedTracks: List<CatalogTrack>,
        limit: Int = 50
    ): List<CatalogTrack> {
        if (limit <= 0) return emptyList()
        val firstByArtist = first.artists.associateBy { it.artist }
        val secondByArtist = second.artists.associateBy { it.artist }
        val shared = first.artists
            .filter { it.artist in secondByArtist }
            .sortedByDescending { (it.score + (secondByArtist[it.artist]?.score ?: 0.0)) }
            .map { it.artist }
        val alternating = mutableListOf<String>()
        val firstOnly = first.artists.filterNot { it.artist in secondByArtist }.map { it.artist }
        val secondOnly = second.artists.filterNot { it.artist in firstByArtist }.map { it.artist }
        val max = maxOf(firstOnly.size, secondOnly.size)
        repeat(max) { index ->
            firstOnly.getOrNull(index)?.let(alternating::add)
            secondOnly.getOrNull(index)?.let(alternating::add)
        }

        val selected = linkedMapOf<String, CatalogTrack>()
        (shared + alternating).forEach { artist ->
            tracksByArtist[artist].orEmpty().forEach { track ->
                if (track.url.isNotBlank()) selected.putIfAbsent(track.url, track)
                if (selected.size >= limit) return selected.values.take(limit)
            }
        }
        relatedTracks.forEach { track ->
            if (track.url.isNotBlank()) selected.putIfAbsent(track.url, track)
            if (selected.size >= limit) return selected.values.take(limit)
        }
        return selected.values.take(limit)
    }
}

/**
 * Sharing is intentionally unavailable until the pair-scoped Supabase table and RLS
 * migration are approved. Only TasteProfile is accepted at this boundary, never history.
 */
interface BlendProfileRepository {
    suspend fun share(pairId: String, profile: TasteProfile): Result<Unit>
    suspend fun partnerProfile(pairId: String): Result<TasteProfile?>
}

object UnavailableBlendProfileRepository : BlendProfileRepository {
    override suspend fun share(pairId: String, profile: TasteProfile): Result<Unit> =
        Result.failure(IllegalStateException("Blend sharing requires an approved pair-profile migration."))

    override suspend fun partnerProfile(pairId: String): Result<TasteProfile?> =
        Result.failure(IllegalStateException("Blend sharing requires an approved pair-profile migration."))
}
