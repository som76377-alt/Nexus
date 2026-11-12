package com.nexus.music.catalog

data class CatalogTrack(
    val url: String,
    val title: String,
    val artist: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val viewCount: Long?
)

/**
 * A resolved stream URL is short-lived. Do not persist audioUrl in a database or playlist;
 * persist CatalogTrack.url and resolve it again immediately before playback.
 */
data class ResolvedAudio(
    val sourceUrl: String,
    val title: String,
    val artist: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val audioUrl: String,
    val averageBitrate: Int?,
    val mimeType: String?,
    val provider: StreamProvider? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val ipFamily: StreamIpFamily? = null
)

enum class StreamIpFamily { IPV4, IPV6 }

enum class YoutubeSearchScope(internal val extractorFilter: String?) {
    EVERYTHING(null),
    MUSIC_SONGS("music_songs"),
    VIDEOS("videos")
}
