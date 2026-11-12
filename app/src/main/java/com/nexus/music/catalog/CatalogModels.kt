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
    /** Per-stream headers; device-local and never part of room state. */
    val requestHeaders: Map<String, String> = emptyMap(),
    /** Provider that produced this short-lived URL; used only for local retry selection. */
    val provider: StreamProvider = StreamProvider.NEWPIPE
)

/** Restrict provider-supplied headers to safe end-to-end request metadata. */
internal fun sanitizeStreamRequestHeaders(headers: Map<String, String>): Map<String, String> {
    val allowed = setOf("user-agent", "referer", "origin", "accept", "accept-language")
    return headers.entries.mapNotNull { (name, value) ->
        val key = name.trim()
        val content = value.trim()
        if (key.lowercase() in allowed && key.isNotEmpty() && content.isNotEmpty() &&
            key.none { it <= ' ' || it == ':' || it == '\r' || it == '\n' } &&
            content.none { it == '\r' || it == '\n' }
        ) key to content else null
    }.toMap()
}

enum class YoutubeSearchScope(internal val extractorFilter: String?) {
    EVERYTHING(null),
    MUSIC_SONGS("music_songs"),
    VIDEOS("videos")
}
