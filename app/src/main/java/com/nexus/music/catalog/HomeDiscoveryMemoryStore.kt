package com.nexus.music.catalog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class HomeDiscoveryMemory(
    val playedArtistWeights: Map<String, Int>,
    val dismissedTrackUrls: Set<String>,
    val dislikedArtists: Set<String>,
    val recommendedTrackUrls: Set<String>
)

/** Local-only recommendation memory. Temporary audio stream URLs are never stored. */
internal class HomeDiscoveryMemoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "home_discovery_memory_v1",
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    fun snapshot(): HomeDiscoveryMemory = synchronized(lock) { readMemory() }

    fun recordPlayed(track: CatalogTrack) = synchronized(lock) {
        val current = readMemory()
        val weights = current.playedArtistWeights.toMutableMap()
        normalizeArtistName(track.artist)?.let { artist ->
            weights[artist] = ((weights[artist] ?: 0) + 2).coerceAtMost(40)
        }
        writeMemory(current.copy(playedArtistWeights = weights.entries
            .sortedByDescending { it.value }.take(MAX_ARTISTS).associate { it.toPair() }))
    }

    fun recordNotInterested(track: CatalogTrack) = synchronized(lock) {
        val current = readMemory()
        val dismissed = (current.dismissedTrackUrls + track.url).takeLastSet(MAX_TRACKS)
        val disliked = normalizeArtistName(track.artist)?.let { current.dislikedArtists + it }
            ?: current.dislikedArtists
        writeMemory(current.copy(
            dismissedTrackUrls = dismissed,
            dislikedArtists = disliked.takeLastSet(MAX_ARTISTS)
        ))
    }

    fun recordRecommended(tracks: List<CatalogTrack>) = synchronized(lock) {
        val current = readMemory()
        val urls = (current.recommendedTrackUrls + tracks.map { it.url }).takeLastSet(MAX_TRACKS)
        writeMemory(current.copy(recommendedTrackUrls = urls))
    }

    private fun readMemory(): HomeDiscoveryMemory = runCatching {
        val root = JSONObject(preferences.getString(KEY, "{}") ?: "{}")
        val weightsJson = root.optJSONObject("played_artist_weights") ?: JSONObject()
        val weights = buildMap {
            val keys = weightsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val weight = weightsJson.optInt(key, 0)
                if (key.isNotBlank() && weight > 0) put(key, weight)
            }
        }
        HomeDiscoveryMemory(
            playedArtistWeights = weights,
            dismissedTrackUrls = root.optJSONArray("dismissed_tracks").toStringSet(),
            dislikedArtists = root.optJSONArray("disliked_artists").toStringSet(),
            recommendedTrackUrls = root.optJSONArray("recommended_tracks").toStringSet()
        )
    }.getOrDefault(HomeDiscoveryMemory(emptyMap(), emptySet(), emptySet(), emptySet()))

    private fun writeMemory(memory: HomeDiscoveryMemory) {
        val weights = JSONObject()
        memory.playedArtistWeights.forEach { (artist, weight) -> weights.put(artist, weight) }
        val root = JSONObject()
            .put("played_artist_weights", weights)
            .put("dismissed_tracks", memory.dismissedTrackUrls.toJsonArray())
            .put("disliked_artists", memory.dislikedArtists.toJsonArray())
            .put("recommended_tracks", memory.recommendedTrackUrls.toJsonArray())
        preferences.edit().putString(KEY, root.toString()).apply()
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) {
                optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun Set<String>.toJsonArray(): JSONArray = JSONArray().also { array ->
        forEach { value -> array.put(value) }
    }

    private fun Set<String>.takeLastSet(limit: Int): Set<String> = toList().takeLast(limit).toSet()

    private companion object {
        const val KEY = "state_v1"
        const val MAX_ARTISTS = 80
        const val MAX_TRACKS = 500
    }
}

internal fun normalizeArtistName(value: String?): String? =
    value?.trim()?.lowercase()
        ?.takeIf { it.isNotEmpty() && it !in setOf("unknown artist", "various artists", "unknown", "n/a") }
