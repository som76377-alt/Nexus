package com.nexus.music.recommendations

import android.content.Context
import com.nexus.music.catalog.CatalogTrack
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

data class GenreBucket(
    val id: String,
    val label: String,
    val query: String
)

/**
 * Deterministic 60/40 taste/exploration mixer. The five-bucket floor is enforced
 * before the remaining exploration picks are filled.
 */
object RecommendationMixer {
    fun mix(
        tasteTracks: List<CatalogTrack>,
        genreTracks: Map<String, List<CatalogTrack>>,
        seed: Long,
        excludedIds: Set<String>,
        limit: Int = 20
    ): List<CatalogTrack> {
        if (limit <= 0) return emptyList()
        val buckets = genreTracks.entries
            .filter { it.key.isNotBlank() && it.value.isNotEmpty() }
            .shuffled(Random(seed))
        if (buckets.size < 5) return emptyList()

        val random = Random(seed xor 0x4E45585553L)
        val seen = excludedIds.toMutableSet()
        val taste = tasteTracks
            .asSequence()
            .filter { it.url.isNotBlank() && it.url !in seen }
            .distinctBy { it.url }
            .toList()
            .shuffled(random)
            .take((limit * 0.60f).toInt())
            .onEach { seen += it.url }

        val explorationLimit = (limit - taste.size).coerceAtLeast((limit * 0.40f).toInt())
        val exploration = mutableListOf<CatalogTrack>()

        // First take one candidate from each genre so the batch cannot collapse to one theme.
        buckets.forEach { bucket ->
            if (exploration.size >= minOf(5, explorationLimit)) return@forEach
            val candidate = bucket.value
                .asSequence()
                .filter { it.url.isNotBlank() && it.url !in seen }
                .distinctBy { it.url }
                .shuffled(random)
                .firstOrNull()
            if (candidate != null) {
                exploration += candidate
                seen += candidate.url
            }
        }

        // Fill the remainder from randomly ordered genre pools.
        while (exploration.size < explorationLimit) {
            val candidates = buckets
                .flatMap { it.value }
                .filter { it.url.isNotBlank() && it.url !in seen }
                .distinctBy { it.url }
            if (candidates.isEmpty()) break
            val candidate = candidates[random.nextInt(candidates.size)]
            exploration += candidate
            seen += candidate.url
        }

        return (taste + exploration).shuffled(random).take(limit)
    }
}

/** Stores the last 300 displayed track IDs; entries expire after 30 days. */
class RecommendationHistoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("nexus_recommendations", Context.MODE_PRIVATE)

    fun shownIds(nowMs: Long = System.currentTimeMillis()): Set<String> {
        val raw = preferences.getString(HISTORY_KEY, null) ?: return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = item.optString("url")
                    val timestamp = item.optLong("time", 0L)
                    if (url.isNotBlank() && timestamp > nowMs - HISTORY_TTL_MS) add(url)
                }
            }
        }.getOrDefault(emptySet())
    }

    fun remember(tracks: List<CatalogTrack>, nowMs: Long = System.currentTimeMillis()) {
        val entries = JSONArray()
        val latest = tracks.asSequence()
            .map { it.url to nowMs }
            .filter { it.first.isNotBlank() }
            .distinctBy { it.first }
            .toList()
        val latestIds = latest.mapTo(mutableSetOf()) { it.first }
        val existing = readHistory()
            .filter { it.second > nowMs - HISTORY_TTL_MS && it.first !in latestIds }
        val merged = (latest + existing).take(MAX_HISTORY)
        merged.forEach { (url, time) ->
            entries.put(JSONObject().put("url", url).put("time", time))
        }
        preferences.edit().putString(HISTORY_KEY, entries.toString()).apply()
    }

    private fun readHistory(): List<Pair<String, Long>> {
        val raw = preferences.getString(HISTORY_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = item.optString("url")
                    if (url.isNotBlank()) add(url to item.optLong("time", 0L))
                }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val HISTORY_KEY = "shown_tracks_v1"
        const val MAX_HISTORY = 300
        const val HISTORY_TTL_MS = 30L * 24L * 60L * 60L * 1000L
    }
}

/** Six-hour per-bucket result cache; kept separate from playback's audio cache. */
class RecommendationCacheStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("nexus_recommendation_cache", Context.MODE_PRIVATE)

    fun get(bucketId: String, nowMs: Long = System.currentTimeMillis()): List<CatalogTrack>? {
        val key = key(bucketId)
        val storedAt = preferences.getLong(key + "_time", 0L)
        if (storedAt <= 0L || nowMs - storedAt >= CACHE_TTL_MS) return null
        val raw = preferences.getString(key + "_items", null) ?: return null
        return decode(raw).takeIf { it.isNotEmpty() }
    }

    fun put(bucketId: String, tracks: List<CatalogTrack>, nowMs: Long = System.currentTimeMillis()) {
        if (tracks.isEmpty()) return
        preferences.edit()
            .putLong(key(bucketId) + "_time", nowMs)
            .putString(key(bucketId) + "_items", encode(tracks))
            .apply()
    }

    private fun key(id: String) = "bucket_" + id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun encode(tracks: List<CatalogTrack>): String {
        val array = JSONArray()
        tracks.take(40).forEach { track ->
            array.put(
                JSONObject()
                    .put("url", track.url)
                    .put("title", track.title)
                    .put("artist", track.artist ?: "")
                    .put("thumbnail", track.thumbnailUrl ?: "")
                    .put("duration", track.durationSeconds ?: -1L)
                    .put("views", track.viewCount ?: -1L)
            )
        }
        return array.toString()
    }

    private fun decode(raw: String): List<CatalogTrack> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = item.optString("url")
                if (!url.startsWith("https://")) continue
                add(
                    CatalogTrack(
                        url = url,
                        title = item.optString("title"),
                        artist = item.optString("artist").takeIf(String::isNotBlank),
                        thumbnailUrl = item.optString("thumbnail").takeIf { it.startsWith("https://") },
                        durationSeconds = item.optLong("duration", -1L).takeIf { it >= 0L },
                        viewCount = item.optLong("views", -1L).takeIf { it >= 0L }
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
    }
}
