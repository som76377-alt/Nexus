package com.nexus.music.library

import android.content.Context
import com.nexus.music.catalog.CatalogTrack
import com.nexus.music.playback.PlaybackSnapshot

/** Builds stable catalogue metadata from the active MediaSession item, never its audio stream URL. */
fun currentCatalogTrack(context: Context, snapshot: PlaybackSnapshot): CatalogTrack? {
    if (!snapshot.connected || snapshot.title.isBlank()) return null
    val videoId = YoutubeVideoId.fromUrl(snapshot.sourceId) ?: return null
    val preferences = context.applicationContext.getSharedPreferences("nexus_home", Context.MODE_PRIVATE)
    val savedUrl = preferences.getString("last_track_url", null)
    val savedMetadataMatches = savedUrl?.let(YoutubeVideoId::fromUrl) == videoId

    val savedDuration = if (savedMetadataMatches) {
        preferences.getLong("last_track_duration", -1L).takeIf { it >= 0L }
    } else null

    return CatalogTrack(
        url = snapshot.sourceId,
        title = snapshot.title,
        artist = snapshot.artist.takeIf(String::isNotBlank)
            ?: if (savedMetadataMatches) preferences.getString("last_track_artist", null) else null,
        thumbnailUrl = if (savedMetadataMatches) {
            preferences.getString("last_track_thumbnail", null)?.takeIf(String::isNotBlank)
        } else null,
        durationSeconds = snapshot.durationMs.takeIf { it > 0L }?.div(1_000L) ?: savedDuration,
        viewCount = null
    )
}
