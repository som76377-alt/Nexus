package com.nexus.music.library

import com.nexus.music.catalog.CatalogTrack

/** Local-only collection storage. Resolved audio stream URLs are deliberately never accepted. */
interface LibraryRepository {
    fun setLiked(track: CatalogTrack, liked: Boolean): Boolean
    fun setSaved(track: CatalogTrack, saved: Boolean): Boolean
    fun isLiked(videoId: String): Boolean
    fun isSaved(videoId: String): Boolean
    fun likedTracks(): List<LibraryTrack>
    fun savedTracks(): List<LibraryTrack>

    fun createPlaylist(name: String): Long
    fun renamePlaylist(id: Long, name: String): Boolean
    fun deletePlaylist(id: Long): Boolean
    fun playlists(): List<PlaylistSummary>
    fun addToPlaylist(playlistId: Long, track: CatalogTrack): Boolean
    fun removeFromPlaylist(playlistId: Long, videoId: String): Boolean
    fun playlistTracks(playlistId: Long): List<LibraryTrack>

    fun recordHistory(track: CatalogTrack, playedAtMillis: Long): Boolean
    fun history(limit: Int = 100): List<PlayedTrack>
}
