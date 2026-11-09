package com.nexus.music.library

import com.nexus.music.catalog.CatalogTrack

/** Stable YouTube identity is the video id, never a transient resolved stream URL. */
data class LibraryTrack(
    val id: String,
    val url: String,
    val title: String,
    val artist: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?
) {
    fun toCatalogTrack() = CatalogTrack(
        url = url,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds,
        viewCount = null
    )

    companion object {
        fun from(track: CatalogTrack): LibraryTrack? {
            val id = YoutubeVideoId.fromUrl(track.url) ?: return null
            return LibraryTrack(id, track.url, track.title, track.artist, track.thumbnailUrl, track.durationSeconds)
        }
    }
}

data class PlaylistSummary(val id: Long, val name: String, val trackCount: Int)

data class PlayedTrack(val track: LibraryTrack, val playedAtMillis: Long)

/** Extracts only canonical 11-character YouTube video ids. */
object YoutubeVideoId {
    private val validId = Regex("^[A-Za-z0-9_-]{11}$")

    fun fromUrl(raw: String): String? = runCatching {
        val uri = java.net.URI(raw)
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host !in setOf("youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")) return null
        val path = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val candidate = when (host) {
            "youtu.be" -> path.firstOrNull()
            else -> {
                val queryId = uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { pair ->
                    val parts = pair.split('=', limit = 2)
                    if (parts.firstOrNull() == "v") parts.getOrNull(1)?.let(::decode) else null
                }
                queryId ?: when (path.firstOrNull()) {
                    "shorts", "embed", "live" -> path.getOrNull(1)
                    else -> null
                }
            }
        }
        candidate?.takeIf(validId::matches)
    }.getOrNull()

    private fun decode(value: String): String =
        java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
}
