package com.nexus.music.library

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.nexus.music.catalog.CatalogTrack
import com.nexus.music.catalog.CatalogUrlValidator

/**
 * Device-local collection. Only canonical source URLs and display metadata are persisted;
 * temporary resolved audio URLs are never stored. The current repository has no Room
 * dependency, so this keeps the existing SharedPreferences format until a tested Room
 * migration can be introduced without losing existing Saved/History data.
 */
data class PlayHistoryEvent(val track: CatalogTrack, val playedAtMs: Long)

class LocalLibraryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("nexus_library", Context.MODE_PRIVATE)
    private val lock = Any()

    fun savedTracks(): List<CatalogTrack> = read(SAVED_KEY)
    fun recentTracks(): List<CatalogTrack> = read(RECENT_KEY)
    fun watchLaterTracks(): List<CatalogTrack> = read(WATCH_LATER_KEY)

    fun playHistory(): List<PlayHistoryEvent> = readHistoryEvents()
    fun isSaved(track: CatalogTrack): Boolean = savedTracks().any { it.url == track.url }
    fun isWatchLater(track: CatalogTrack): Boolean = watchLaterTracks().any { it.url == track.url }

    fun save(track: CatalogTrack) {
        if (!valid(track)) return
        write(SAVED_KEY, (listOf(track) + savedTracks().filterNot { it.url == track.url }).take(MAX_SAVED))
    }

    fun remove(track: CatalogTrack) {
        write(SAVED_KEY, savedTracks().filterNot { it.url == track.url })
    }

    fun recordPlayed(track: CatalogTrack, playedAtMs: Long = System.currentTimeMillis()) {
        if (!valid(track)) return
        val updatedTracks = (listOf(track) + recentTracks().filterNot { it.url == track.url }).take(MAX_RECENT)
        write(RECENT_KEY, updatedTracks)
        val updatedTimes = readHistoryEvents()
            .filterNot { it.track.url == track.url }
            .plus(PlayHistoryEvent(track, playedAtMs))
            .sortedByDescending { it.playedAtMs }
            .take(MAX_RECENT)
        val array = JSONArray()
        updatedTimes.forEach { event ->
            array.put(JSONObject().put("url", event.track.url).put("played_at", event.playedAtMs))
        }
        synchronized(lock) {
            preferences.edit().putString(PLAY_TIMESTAMPS_KEY, array.toString()).apply()
        }
    }

    private fun readHistoryEvents(): List<PlayHistoryEvent> {
        val tracksByUrl = recentTracks().associateBy { it.url }
        val raw = synchronized(lock) { preferences.getString(PLAY_TIMESTAMPS_KEY, null) }
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = item.optString("url")
                    val track = tracksByUrl[url] ?: continue
                    val playedAt = item.optLong("played_at", 0L)
                    if (playedAt > 0L) add(PlayHistoryEvent(track, playedAt))
                }
            }.sortedByDescending { it.playedAtMs }
        }.getOrDefault(emptyList())
    }

    fun addToWatchLater(track: CatalogTrack) {
        if (!valid(track)) return
        write(WATCH_LATER_KEY, (listOf(track) + watchLaterTracks().filterNot { it.url == track.url }).take(MAX_PLAYLIST_TRACKS))
    }

    fun removeFromWatchLater(track: CatalogTrack) {
        write(WATCH_LATER_KEY, watchLaterTracks().filterNot { it.url == track.url })
    }

    fun playlistNames(): List<String> = synchronized(lock) {
        val raw = preferences.getString(PLAYLIST_NAMES_KEY, null) ?: return@synchronized emptyList()
        runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val name = array.optString(index).trim()
                    if (name.isNotEmpty() && name != WATCH_LATER_NAME && name !in this) add(name)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun createPlaylist(name: String): Boolean {
        val normalized = name.trim().take(MAX_PLAYLIST_NAME)
        if (normalized.isBlank() || normalized.equals(WATCH_LATER_NAME, ignoreCase = true)) return false
        synchronized(lock) {
            val names = playlistNames()
            if (names.any { it.equals(normalized, ignoreCase = true) }) return false
            val updated = names + normalized
            preferences.edit().putString(PLAYLIST_NAMES_KEY, JSONArray(updated).toString()).apply()
            preferences.edit().putString(playlistKey(normalized), JSONArray().toString()).apply()
        }
        return true
    }

    fun removePlaylist(name: String) {
        synchronized(lock) {
            preferences.edit().remove(playlistKey(name)).apply()
            preferences.edit().putString(
                PLAYLIST_NAMES_KEY,
                JSONArray(playlistNames().filterNot { it == name }).toString()
            ).apply()
        }
    }

    fun playlistTracks(name: String): List<CatalogTrack> =
        if (name == WATCH_LATER_NAME) watchLaterTracks() else read(playlistKey(name))

    fun addToPlaylist(track: CatalogTrack, name: String) {
        if (!valid(track) || name.isBlank()) return
        if (name == WATCH_LATER_NAME) {
            addToWatchLater(track)
            return
        }
        if (name !in playlistNames()) return
        val key = playlistKey(name)
        write(key, (listOf(track) + read(key).filterNot { it.url == track.url }).take(MAX_PLAYLIST_TRACKS))
    }

    fun removeFromPlaylist(track: CatalogTrack, name: String) {
        if (name == WATCH_LATER_NAME) {
            removeFromWatchLater(track)
            return
        }
        val key = playlistKey(name)
        write(key, read(key).filterNot { it.url == track.url })
    }

    private fun valid(track: CatalogTrack): Boolean =
        CatalogUrlValidator.isValidHttpsUrl(track.url)

    private fun playlistKey(name: String): String =
        "playlist_v1_" + name.lowercase().map { char ->
            if (char.isLetterOrDigit()) char else '_'
        }.joinToString("").take(MAX_PLAYLIST_NAME)

    private fun read(key: String): List<CatalogTrack> = synchronized(lock) {
        val raw = preferences.getString(key, null) ?: return@synchronized emptyList()
        runCatching {
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

    private fun write(key: String, tracks: List<CatalogTrack>) = synchronized(lock) {
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
        const val PLAY_TIMESTAMPS_KEY = "recent_play_timestamps_v1"
        const val WATCH_LATER_KEY = "watch_later_v1"
        const val PLAYLIST_NAMES_KEY = "playlist_names_v1"
        const val WATCH_LATER_NAME = "Watch later"
        const val MAX_SAVED = 500
        const val MAX_RECENT = 300
        const val MAX_PLAYLIST_TRACKS = 500
        const val MAX_PLAYLIST_NAME = 48
    }
}
