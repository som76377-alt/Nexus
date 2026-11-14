package com.nexus.music.catalog

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Extracts a YouTube video ID without rewriting or replacing the original source URL. */
object YoutubeVideoId {
    private val validId = Regex("^[A-Za-z0-9_-]{11}$")

    fun isValid(value: String): Boolean = validId.matches(value)

    fun fromUrl(value: String): String? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        val normalized = if (raw.startsWith("/")) "https://www.youtube.com$raw" else raw
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
        val candidate = when {
            host == "youtu.be" -> path.firstOrNull()
            host == "youtube.com" || host.endsWith(".youtube.com") -> when (path.firstOrNull()) {
                "watch" -> queryParameter(uri.rawQuery, "v")
                "shorts", "embed", "live" -> path.getOrNull(1)
                else -> queryParameter(uri.rawQuery, "v")
            }
            else -> null
        } ?: return null
        return candidate.takeIf(::isValid)
    }

    private fun queryParameter(query: String?, name: String): String? =
        query?.split('&')?.firstNotNullOfOrNull { pair ->
            val separator = pair.indexOf('=')
            if (separator < 0) return@firstNotNullOfOrNull null
            val key = decode(pair.substring(0, separator))
            if (key == name) decode(pair.substring(separator + 1)) else null
        }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}

/** Computed identity used for search deduplication; CatalogTrack.url remains untouched. */
val CatalogTrack.videoId: String?
    get() = YoutubeVideoId.fromUrl(url)
