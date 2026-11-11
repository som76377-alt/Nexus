package com.nexus.music.library

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.nexus.music.catalog.CatalogTrack
import com.nexus.music.catalog.CatalogUrlValidator

/**
 * Small local-only library. Stores canonical source URLs and display metadata only;
 * resolved/temporary audio stream URLs are never persisted.
 */
class LocalLibraryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("nexus_library", Context.MODE_PRIVATE)

    fun savedTracks(): List<CatalogTrack> = read(SAVED_KEY)
    fun recentTracks(): List<CatalogTrack> = read(RECENT_KEY)

    fun isSaved(track: CatalogTrack): Boolean =
        savedTracks().any { it.url == track.url }

    fun save(track: CatalogTrack) {
        if (!CatalogUrlValidator.isValidHttpsUrl(track.url)) return
        val tracks = savedTracks().filterNot { it.url == track.url }
        write(SAVED_KEY, (listOf(track) + tracks).take(MAX_SAVED))
    }

    fun remove(track: CatalogTrack) {
        write(SAVED_KEY, savedTracks().filterNot { it.url == track.url })
    }

    fun recordPlayed(track: CatalogTrack) {
        if (!CatalogUrlValidator.isValidHttpsUrl(track.url)) return
        val tracks = recentTracks().filterNot { it.url == track.url }
        write(RECENT_KEY, (listOf(track) + tracks).take(MAX_RECENT))
    }

    private fun read(key: String): List<CatalogTrack> {
        val raw = preferences.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = item.optString("url")
                    if (!CatalogUrlValidator.isValidHttpsUrl(url)) continue
                    add(
                        CatalogTrack(
                            url = url,
                            title = item.optString("title"),
                            artist = item.optString("artist").takeIf(String::isNotBlank),
                            thumbnailUrl = item.optString("thumbnail").takeIf(CatalogUrlValidator::isValidHttpsUrl),
                            durationSeconds = item.optLong("duration", -1L).takeIf { it >= 0L },
                            viewCount = item.optLong("views", -1L).takeIf { it >= 0L }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(key: String, tracks: List<CatalogTrack>) {
        val array = JSONArray()
        tracks.forEach { track ->
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
        preferences.edit().putString(key, array.toString()).apply()
    }

    private companion object {
        const val SAVED_KEY = "saved_tracks_v1"
        const val RECENT_KEY = "recent_tracks_v1"
        const val MAX_SAVED = 500
        const val MAX_RECENT = 30
    }
}
