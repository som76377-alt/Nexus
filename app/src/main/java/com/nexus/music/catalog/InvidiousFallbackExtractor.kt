package com.nexus.music.catalog

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Independent extractor fallback backed by the vetted public Invidious API instances.
 * Unlike Piped, Invidious uses a separate extraction implementation.
 */
internal class InvidiousFallbackExtractor(
    private val client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val instanceUrls: List<String> = DEFAULT_INSTANCES
) {
    private val unhealthyUntil = mutableMapOf<String, Long>()

    fun search(query: String, scope: YoutubeSearchScope): List<CatalogTrack> {
        val filter = if (scope == YoutubeSearchScope.EVERYTHING) "all" else "video"
        return withInstance { base ->
            val url = "$base/api/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("type", filter)
                .addQueryParameter("region", "IN")
                .build()
            val items = getArray(url.toString(), base)
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    if (item.optString("type") != "video") continue
                    val videoId = item.optString("videoId")
                        .takeIf { VIDEO_ID.matches(it) } ?: continue
                    val title = item.optString("title").takeIf(String::isNotBlank) ?: continue
                    val thumbnails = item.optJSONArray("videoThumbnails") ?: JSONArray()
                    val thumbnail = (0 until thumbnails.length())
                        .mapNotNull { thumbnails.optJSONObject(it)?.optString("url") }
                        .firstOrNull(CatalogUrlValidator::isValidHttpsUrl)
                    add(
                        CatalogTrack(
                            url = youtubeUrl(videoId),
                            title = title,
                            artist = item.optString("author").takeIf(String::isNotBlank),
                            thumbnailUrl = thumbnail,
                            durationSeconds = item.optLong("lengthSeconds", -1L).takeIf { it >= 0L },
                            viewCount = item.optLong("viewCount", -1L).takeIf { it >= 0L }
                        )
                    )
                }
            }
        }.distinctBy { it.url }
    }

    fun resolveAudio(videoUrl: String): ResolvedAudio {
        val videoId = extractVideoId(videoUrl)
            ?: throw IOException("Invidious could not identify this YouTube video.")
        return withInstance { base ->
            val json = getObject("$base/api/v1/videos/$videoId?region=IN", base)
            val formats = json.optJSONArray("adaptiveFormats") ?: JSONArray()
            val selected = (0 until formats.length())
                .mapNotNull { formats.optJSONObject(it) }
                .mapNotNull { format ->
                    val mime = format.optString("type").takeIf(String::isNotBlank)
                    val url = format.optString("url")
                    if (mime?.startsWith("audio/", ignoreCase = true) != true ||
                        !CatalogUrlValidator.isValidHttpsUrl(url)
                    ) {
                        null
                    } else {
                        Triple(format, url, mime)
                    }
                }
                .maxByOrNull { it.first.optString("bitrate").toIntOrNull() ?: 0 }
                ?: throw IOException("Invidious returned no directly playable audio stream.")

            val thumbnails = json.optJSONArray("videoThumbnails") ?: JSONArray()
            val thumbnail = (0 until thumbnails.length())
                .mapNotNull { thumbnails.optJSONObject(it)?.optString("url") }
                .firstOrNull(CatalogUrlValidator::isValidHttpsUrl)
            val format = selected.first
            ResolvedAudio(
                sourceUrl = youtubeUrl(videoId),
                title = json.optString("title").takeIf(String::isNotBlank)
                    ?: throw IOException("Invidious returned no track title."),
                artist = json.optString("author").takeIf(String::isNotBlank),
                thumbnailUrl = thumbnail,
                durationSeconds = json.optLong("lengthSeconds", -1L).takeIf { it >= 0L },
                audioUrl = selected.second,
                averageBitrate = format.optString("bitrate").toIntOrNull()?.takeIf { it > 0 },
                mimeType = selected.third
            )
        }
    }

    private fun <T> withInstance(block: (String) -> T): T {
        val failures = mutableListOf<Exception>()
        for (base in instanceUrls) {
            if (isCoolingDown(base)) continue
            try {
                return block(base)
            } catch (error: Exception) {
                markUnhealthy(base)
                failures += error
            }
        }
        val failure = IOException("All vetted Invidious fallback instances failed.")
        failures.forEach(failure::addSuppressed)
        throw failure
    }

    private fun getArray(url: String, base: String): JSONArray {
        val body = getBody(url, base)
        return try {
            JSONArray(body)
        } catch (error: Exception) {
            throw IOException("Invidious returned an invalid search response from $base.", error)
        }
    }

    private fun getObject(url: String, base: String): JSONObject {
        val body = getBody(url, base)
        return try {
            JSONObject(body)
        } catch (error: Exception) {
            throw IOException("Invidious returned an invalid stream response from $base.", error)
        }
    }

    private fun getBody(url: String, base: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Nexus Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Invidious returned HTTP ${response.code} from $base.")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun isCoolingDown(base: String): Boolean =
        synchronized(unhealthyUntil) { (unhealthyUntil[base] ?: 0L) > clock() }

    private fun markUnhealthy(base: String) {
        synchronized(unhealthyUntil) {
            unhealthyUntil[base] = clock() + INSTANCE_COOLDOWN_MS
        }
    }

    private fun extractVideoId(value: String): String? {
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return null
        val queryId = uri.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("v=") }
            ?.substringAfter('=')
        val candidate = queryId ?: uri.path.orEmpty().substringAfterLast('/')
        return candidate.takeIf { VIDEO_ID.matches(it) }
    }

    private fun youtubeUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    companion object {
        // Only instances currently listed by the Invidious maintainers are trusted.
        private val DEFAULT_INSTANCES = listOf(
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://yt.chocolatemoo53.com",
            "https://invidious.tiekoetter.com"
        )
        private const val INSTANCE_COOLDOWN_MS = 2 * 60 * 1000L
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
