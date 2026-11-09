package com.nexus.music.library

import com.nexus.music.catalog.CatalogTrack

/** Deterministic fake used by JVM tests and UI previews. */
class InMemoryLibraryRepository : LibraryRepository {
    private val tracks = linkedMapOf<String, LibraryTrack>()
    private val liked = linkedMapOf<String, Long>()
    private val saved = linkedMapOf<String, Long>()
    private val playlistRows = linkedMapOf<Long, String>()
    private val playlistItems = linkedMapOf<Long, LinkedHashMap<String, Long>>()
    private val historyRows = mutableListOf<PlayedTrack>()
    private var nextPlaylistId = 1L

    override fun setLiked(track: CatalogTrack, liked: Boolean): Boolean =
        updateMembership(track, this.liked, liked)

    override fun setSaved(track: CatalogTrack, saved: Boolean): Boolean =
        updateMembership(track, this.saved, saved)

    override fun isLiked(videoId: String) = videoId in liked
    override fun isSaved(videoId: String) = videoId in saved
    override fun likedTracks() = orderedMembership(this.liked)
    override fun savedTracks() = orderedMembership(this.saved)

    override fun createPlaylist(name: String): Long {
        val clean = cleanName(name)
        require(playlistRows.values.none { it.equals(clean, ignoreCase = true) }) { "A playlist with that name already exists." }
        val id = nextPlaylistId++
        playlistRows[id] = clean
        playlistItems[id] = linkedMapOf()
        return id
    }

    override fun renamePlaylist(id: Long, name: String): Boolean {
        if (id !in playlistRows) return false
        val clean = cleanName(name)
        require(playlistRows.none { it.key != id && it.value.equals(clean, ignoreCase = true) }) {
            "A playlist with that name already exists."
        }
        playlistRows[id] = clean
        return true
    }

    override fun deletePlaylist(id: Long): Boolean {
        playlistItems.remove(id)
        return playlistRows.remove(id) != null
    }

    override fun playlists() = playlistRows.map { (id, name) ->
        PlaylistSummary(id, name, playlistItems[id]?.size ?: 0)
    }

    override fun addToPlaylist(playlistId: Long, track: CatalogTrack): Boolean {
        val item = persist(track) ?: return false
        val items = playlistItems[playlistId] ?: return false
        if (item.id !in items) items[item.id] = System.currentTimeMillis()
        return true
    }

    override fun removeFromPlaylist(playlistId: Long, videoId: String): Boolean =
        playlistItems[playlistId]?.remove(videoId) != null

    override fun playlistTracks(playlistId: Long): List<LibraryTrack> =
        playlistItems[playlistId].orEmpty().keys.mapNotNull(tracks::get)

    override fun recordHistory(track: CatalogTrack, playedAtMillis: Long): Boolean {
        val item = persist(track) ?: return false
        historyRows += PlayedTrack(item, playedAtMillis.coerceAtLeast(0L))
        return true
    }

    override fun history(limit: Int): List<PlayedTrack> =
        historyRows.sortedByDescending { it.playedAtMillis }.take(limit.coerceIn(1, 500))

    private fun updateMembership(track: CatalogTrack, membership: MutableMap<String, Long>, enabled: Boolean): Boolean {
        val item = persist(track) ?: return false
        if (enabled) membership.putIfAbsent(item.id, System.currentTimeMillis()) else membership.remove(item.id)
        return true
    }

    private fun persist(track: CatalogTrack): LibraryTrack? =
        LibraryTrack.from(track)?.also { tracks[it.id] = it }

    private fun orderedMembership(membership: Map<String, Long>): List<LibraryTrack> =
        membership.entries.sortedByDescending { it.value }.mapNotNull { tracks[it.key] }

    private fun cleanName(name: String): String = name.trim().also {
        require(it.isNotEmpty()) { "Playlist name cannot be empty." }
        require(it.length <= 80) { "Playlist name must be 80 characters or fewer." }
    }
}
