package com.nexus.music.playback

import java.net.URI
import java.net.URLDecoder

/**
 * Stable identity and expiry rules for cached YouTube audio.
 *
 * Stream URLs are temporary and must never be used as cache keys. A cache entry is
 * addressable only when both the canonical video ID and extracted itag are known.
 */
internal object AudioCachePolicy {
    const val DEFAULT_URL_TTL_MS = 30 * 60_000L
    const val STALE_EARLY_MS = 10 * 60_000L

    fun videoId(sourceUrl: String): String? = runCatching {
        val uri = URI(sourceUrl)
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val path = uri.path.orEmpty().trim('/')
        val id = when {
            host == "youtu.be" -> path.substringBefore('/').takeIf(String::isNotBlank)
            (host == "youtube.com" || host.endsWith(".youtube.com")) -> {
                val segments = path.split('/').filter(String::isNotBlank)
                when {
                    path == "watch" -> query(uri.rawQuery)["v"]
                    segments.size >= 2 && segments[0] in setOf("shorts", "embed", "live") -> segments[1]
                    else -> null
                }
            }
            else -> null
        }
        id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,}")) }
    }.getOrNull()

    fun itag(audioUrl: String): String? = runCatching {
        query(URI(audioUrl).rawQuery)["itag"]?.takeIf { it.matches(Regex("\\d{1,6}")) }
    }.getOrNull()

    fun cacheKey(sourceUrl: String, audioUrl: String): String? {
        val videoId = videoId(sourceUrl) ?: return null
        val itag = itag(audioUrl) ?: return null
        return "$videoId:$itag"
    }

    fun expiryEpochMs(audioUrl: String, nowEpochMs: Long): Long {
        val expirySeconds = runCatching {
            query(URI(audioUrl).rawQuery)["expire"]?.toLongOrNull()
        }.getOrNull()
        return expirySeconds?.takeIf { it > 0L }?.let { it * 1_000L }
            ?: (nowEpochMs + DEFAULT_URL_TTL_MS)
    }

    /** A URL is usable only until ten minutes before its actual expiry. */
    fun isUrlFresh(expiryEpochMs: Long, nowEpochMs: Long): Boolean =
        expiryEpochMs - STALE_EARLY_MS > nowEpochMs

    /** Unknown length is never treated as a complete offline entry. */
    fun isFullyCached(contentLength: Long, cachedLength: Long): Boolean =
        contentLength > 0L && cachedLength >= contentLength

    private fun query(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split('&')
            .filter(String::isNotBlank)
            .mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                val key = URLDecoder.decode(pieces[0], "UTF-8")
                val value = URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
                key to value
            }.toMap()
}
