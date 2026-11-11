package com.nexus.music.catalog

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Server-side extraction fallback. Piped instances run their own extractor and expose
 * normalized search/stream responses, so this remains independent of the on-device NewPipe
 * request path (including its IP-specific YouTube challenges).
 */
internal class PipedFallbackExtractor(
    private val client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val instanceLock = Any()
    private var cachedInstances: List<String> = emptyList()
    private var instancesLoadedAt = 0L
    private val unhealthyUntil = mutableMapOf<String, Long>()

    fun search(query: String, scope: YoutubeSearchScope): List<CatalogTrack> {
        val filter = when (scope) {
            YoutubeSearchScope.EVERYTHING -> "all"
            YoutubeSearchScope.MUSIC_SONGS -> "music_songs"
            YoutubeSearchScope.VIDEOS -> "videos"
        }
        return withInstance { base ->
            val url = "$base/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("filter", filter)
                .build()
            val json = getJson(url.toString(), base)
            val items = json.optJSONArray("items") ?: JSONArray()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val videoId = extractVideoId(item.optString("url")) ?: continue
                    val title = item.optString("title").takeIf(String::isNotBlank) ?: continue
                    add(
                        CatalogTrack(
                            url = youtubeUrl(videoId),
                            title = title,
                            artist = item.optString("uploaderName").takeIf(String::isNotBlank),
                            thumbnailUrl = item.optString("thumbnail")
                                .takeIf(CatalogUrlValidator::isValidHttpsUrl),
                            durationSeconds = item.optLong("duration", -1L).takeIf { it >= 0L },
                            viewCount = item.optLong("views", -1L).takeIf { it >= 0L }
                        )
                    )
                }
            }
        }.distinctBy { it.url }
    }

    fun resolveAudio(videoUrl: String): ResolvedAudio {
        val videoId = extractVideoId(videoUrl)
            ?: throw IOException("The fallback could not identify this YouTube video.")
        return withInstance { base ->
            val json = getJson("$base/streams/$videoId", base)
            val streams = json.optJSONArray("audioStreams") ?: JSONArray()
            val selected = (0 until streams.length())
                .mapNotNull { index -> streams.optJSONObject(index) }
                .mapNotNull { stream ->
                    val url = stream.optString("url")
                    val mime = stream.optString("mimeType").takeIf(String::isNotBlank)
                    val format = stream.optString("format").uppercase()
                    val isAudio = mime?.startsWith("audio/", ignoreCase = true) == true ||
                        format in AUDIO_FORMATS
                    if (!isAudio || !CatalogUrlValidator.isValidHttpsUrl(url)) null
                    else Triple(stream, url, mime)
                }
                .maxByOrNull { it.first.optInt("bitrate", 0) }
                ?: throw IOException("The fallback returned no directly playable audio stream.")

            val stream = selected.first
            ResolvedAudio(
                sourceUrl = youtubeUrl(videoId),
                title = json.optString("title").takeIf(String::isNotBlank)
                    ?: throw IOException("The fallback returned no track title."),
                artist = json.optString("uploader").takeIf(String::isNotBlank),
                thumbnailUrl = json.optString("thumbnailUrl")
                    .takeIf(CatalogUrlValidator::isValidHttpsUrl),
                durationSeconds = json.optLong("duration", -1L).takeIf { it >= 0L },
                audioUrl = selected.second,
                averageBitrate = stream.optInt("bitrate", 0).takeIf { it > 0 },
                mimeType = selected.third
            )
        }
    }

    private fun <T> withInstance(block: (String) -> T): T {
        var lastError: Exception? = null
        for (base in instances()) {
            if (isCoolingDown(base)) continue
            try {
                return block(base)
            } catch (error: Exception) {
                markUnhealthy(base)
                lastError = error
            }
        }
        val message = "All available Piped fallback instances failed."
        throw IOException(message, lastError)
    }

    private fun getJson(url: String, base: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Nexus Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Piped returned HTTP ${response.code} from $base.")
            }
            val body = response.body?.string().orEmpty()
            return try {
                JSONObject(body)
            } catch (error: Exception) {
                throw IOException("Piped returned an invalid response from $base.", error)
            }
        }
    }

    private fun instances(): List<String> = synchronized(instanceLock) {
        val now = clock()
        if (cachedInstances.isNotEmpty() && now - instancesLoadedAt < INSTANCE_CACHE_MS) {
            return@synchronized cachedInstances
        }

        val discovered = discoverInstances()
        cachedInstances = (
            discovered.take(MAX_INSTANCES - OFFICIAL_INSTANCES.size) + OFFICIAL_INSTANCES
        ).distinct().take(MAX_INSTANCES)
        instancesLoadedAt = now
        cachedInstances
    }

    private fun discoverInstances(): List<String> {
        val request = Request.Builder()
            .url(INSTANCE_DIRECTORY)
            .header("Accept", "application/json")
            .header("User-Agent", "Nexus Android")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string().orEmpty()
                val array = runCatching { JSONArray(body) }.getOrNull()
                    ?: JSONObject(body).optJSONArray("instances")
                    ?: return@use emptyList()
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        if (item.has("up") && !item.optBoolean("up")) continue
                        val candidate = item.optString("api_url")
                            .ifBlank { item.optString("apiUrl") }
                            .ifBlank { item.optString("api") }
                        normalizeInstance(candidate)?.let(::add)
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeInstance(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank()) return null
        return "https://${uri.host}"
    }

    private fun isCoolingDown(base: String): Boolean =
        synchronized(instanceLock) { (unhealthyUntil[base] ?: 0L) > clock() }

    private fun markUnhealthy(base: String) {
        synchronized(instanceLock) {
            unhealthyUntil[base] = clock() + INSTANCE_COOLDOWN_MS
        }
    }

    private fun extractVideoId(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val queryId = uri.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("v=") }
            ?.substringAfter('=')
        val candidate = queryId ?: uri.path.orEmpty().substringAfterLast('/')
        return candidate.takeIf { VIDEO_ID.matches(it) }
    }

    private fun youtubeUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    companion object {
        private const val INSTANCE_DIRECTORY = "https://piped-instances.kavin.rocks/"
        private val OFFICIAL_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi-in.kavin.rocks"
        )
        private const val INSTANCE_CACHE_MS = 6 * 60 * 60 * 1000L
        private const val INSTANCE_COOLDOWN_MS = 2 * 60 * 1000L
        private const val MAX_INSTANCES = 8
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private val AUDIO_FORMATS = setOf("M4A", "MP3", "MP2", "OPUS", "OGG", "WEBMA", "WEBMA_OPUS", "FLAC", "ALAC", "WAV")
        
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
