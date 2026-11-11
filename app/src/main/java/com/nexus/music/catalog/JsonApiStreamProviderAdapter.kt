package com.nexus.music.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * JSON API adapters for compatible Piped and Invidious instances.
 *
 * Instance URLs are supplied by configuration; no public instance is treated as permanent.
 * These adapters deliberately resolve only direct audio URLs, never manifests or video streams.
 */
class PipedStreamProviderAdapter(
    endpoints: List<String>,
    client: OkHttpClient = JsonApiStreamProviderAdapter.defaultApiClient()
) : JsonApiStreamProviderAdapter(StreamProvider.PIPED, endpoints, client) {
    override fun searchUrl(base: HttpUrl, query: String): HttpUrl =
        base.newBuilder().addPathSegments("search").addQueryParameter("q", query).build()

    override fun parseSearch(body: String): List<CatalogTrack> {
        val items = JSONArray(body)
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                if (item.optString("type") != "stream") continue
                val rawUrl = item.optString("url").let { if (it.startsWith("/")) "https://www.youtube.com$it" else it }
                val id = JsonApiStreamProviderAdapter.youtubeId(rawUrl) ?: continue
                add(
                    CatalogTrack(
                        url = JsonApiStreamProviderAdapter.youtubeUrl(id),
                        title = item.optString("title").takeIf(String::isNotBlank).orEmpty(),
                        artist = item.optString("uploader").takeIf(String::isNotBlank),
                        thumbnailUrl = item.optString("thumbnail").takeIf(CatalogUrlValidator::isValidHttpsUrl),
                        durationSeconds = item.optLong("duration", -1L).takeIf { it >= 0L },
                        viewCount = item.optLong("views", -1L).takeIf { it >= 0L }
                    )
                )
            }
        }
    }

    override fun resolveUrl(base: HttpUrl, videoId: String): HttpUrl =
        base.newBuilder().addPathSegments("streams").addPathSegment(videoId).build()

    override fun parseResolved(body: String, videoId: String): ResolvedAudio {
        val json = JSONObject(body)
        val streams = json.optJSONArray("audioStreams") ?: JSONArray()
        val selected = (0 until streams.length())
            .mapNotNull(streams::optJSONObject)
            .filter { CatalogUrlValidator.isValidHttpsUrl(it.optString("url")) }
            .filter { it.optString("mimeType").startsWith("audio/", ignoreCase = true) }
            .maxByOrNull { it.optInt("bitrate", 0) }
            ?: throw IOException("Piped returned no directly playable audio stream.")

        return ResolvedAudio(
            sourceUrl = JsonApiStreamProviderAdapter.youtubeUrl(videoId),
            title = json.optString("title").takeIf(String::isNotBlank).orEmpty(),
            artist = json.optString("uploader").takeIf(String::isNotBlank),
            thumbnailUrl = json.optString("thumbnailUrl").takeIf(CatalogUrlValidator::isValidHttpsUrl),
            durationSeconds = json.optLong("duration", -1L).takeIf { it >= 0L },
            audioUrl = selected.getString("url"),
            averageBitrate = selected.optInt("bitrate", 0).takeIf { it > 0 },
            mimeType = selected.optString("mimeType").takeIf(String::isNotBlank)
        )
    }
}

class InvidiousStreamProviderAdapter(
    endpoints: List<String>,
    client: OkHttpClient = JsonApiStreamProviderAdapter.defaultApiClient()
) : JsonApiStreamProviderAdapter(StreamProvider.INVIDIOUS, endpoints, client) {
    override fun searchUrl(base: HttpUrl, query: String): HttpUrl =
        base.newBuilder().addPathSegments("api/v1/search").addQueryParameter("q", query).build()

    override fun parseSearch(body: String): List<CatalogTrack> {
        val items = JSONArray(body)
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("videoId").takeIf(::isValidVideoId) ?: continue
                val thumbnails = item.optJSONArray("videoThumbnails") ?: JSONArray()
                val thumbnail = (0 until thumbnails.length())
                    .mapNotNull(thumbnails::optJSONObject)
                    .firstNotNullOfOrNull { it.optString("url").takeIf(CatalogUrlValidator::isValidHttpsUrl) }
                add(
                    CatalogTrack(
                        url = JsonApiStreamProviderAdapter.youtubeUrl(id),
                        title = item.optString("title").takeIf(String::isNotBlank).orEmpty(),
                        artist = item.optString("author").takeIf(String::isNotBlank),
                        thumbnailUrl = thumbnail,
                        durationSeconds = item.optLong("lengthSeconds", -1L).takeIf { it >= 0L },
                        viewCount = item.optLong("viewCount", -1L).takeIf { it >= 0L }
                    )
                )
            }
        }
    }

    override fun resolveUrl(base: HttpUrl, videoId: String): HttpUrl =
        base.newBuilder().addPathSegments("api/v1/videos").addPathSegment(videoId).build()

    override fun parseResolved(body: String, videoId: String): ResolvedAudio {
        val json = JSONObject(body)
        val formats = json.optJSONArray("adaptiveFormats") ?: JSONArray()
        val selected = (0 until formats.length())
            .mapNotNull(formats::optJSONObject)
            .filter { it.optString("type").substringBefore(';').startsWith("audio/", ignoreCase = true) }
            .filter { CatalogUrlValidator.isValidHttpsUrl(it.optString("url")) }
            .maxByOrNull { it.optInt("bitrate", 0) }
            ?: throw IOException("Invidious returned no directly playable audio stream.")

        val thumbnails = json.optJSONArray("videoThumbnails") ?: JSONArray()
        val thumbnail = (0 until thumbnails.length())
            .mapNotNull(thumbnails::optJSONObject)
            .firstNotNullOfOrNull { it.optString("url").takeIf(CatalogUrlValidator::isValidHttpsUrl) }

        return ResolvedAudio(
            sourceUrl = JsonApiStreamProviderAdapter.youtubeUrl(videoId),
            title = json.optString("title").takeIf(String::isNotBlank).orEmpty(),
            artist = json.optString("author").takeIf(String::isNotBlank),
            thumbnailUrl = thumbnail,
            durationSeconds = json.optLong("lengthSeconds", -1L).takeIf { it >= 0L },
            audioUrl = selected.getString("url"),
            averageBitrate = selected.optInt("bitrate", 0).takeIf { it > 0 },
            mimeType = selected.optString("type").takeIf(String::isNotBlank)
        )
    }
}

/** Shared HTTP, instance failover, and YouTube URL validation for JSON provider APIs. */
abstract class JsonApiStreamProviderAdapter(
    final override val provider: StreamProvider,
    endpoints: List<String>,
    private val client: OkHttpClient
) : StreamProviderAdapter {
    private val endpoints: List<HttpUrl> = endpoints.map { raw ->
        raw.trim().trimEnd('/').toHttpUrl().also {
            require(it.isHttps) { "Provider endpoints must use HTTPS." }
        }
    }.distinct()

    init {
        require(this.endpoints.isNotEmpty()) { "At least one configured provider endpoint is required." }
    }

    final override suspend fun search(
        query: String,
        scope: YoutubeSearchScope
    ): List<CatalogTrack> = withContext(Dispatchers.IO) {
        val normalized = query.trim()
        require(normalized.isNotEmpty()) { "Search query must not be blank." }
        executeAcrossInstances { base ->
            parseSearch(get(searchUrl(base, normalized)))
        }
    }

    final override suspend fun resolveAudio(videoUrl: String): ResolvedAudio = withContext(Dispatchers.IO) {
        val videoId = JsonApiStreamProviderAdapter.youtubeId(videoUrl)
            ?: throw IllegalArgumentException("A supported YouTube video URL is required.")
        executeAcrossInstances { base ->
            parseResolved(get(resolveUrl(base, videoId)), videoId)
        }
    }

    protected abstract fun searchUrl(base: HttpUrl, query: String): HttpUrl
    protected abstract fun parseSearch(body: String): List<CatalogTrack>
    protected abstract fun resolveUrl(base: HttpUrl, videoId: String): HttpUrl
    protected abstract fun parseResolved(body: String, videoId: String): ResolvedAudio

    private fun get(url: HttpUrl): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("${provider.key} API returned HTTP ${response.code}.")
            return response.body?.string() ?: throw IOException("${provider.key} API returned an empty response.")
        }
    }

    private fun <T> executeAcrossInstances(block: (HttpUrl) -> T): T {
        var lastError: IOException? = null
        endpoints.forEach { endpoint ->
            try {
                return block(endpoint)
            } catch (error: IOException) {
                lastError = error
            } catch (error: org.json.JSONException) {
                lastError = IOException("${provider.key} API returned an invalid response.", error)
            }
        }
        throw lastError ?: IOException("No healthy ${provider.key} instance is configured.")
    }

    companion object {
        private const val USER_AGENT = "Nexus/0.1 (Android)"

        internal fun defaultApiClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(false)
                .build()

        internal fun youtubeId(value: String): String? {
            val parsed = runCatching { value.toHttpUrl() }.getOrNull() ?: return null
            val host = parsed.host.lowercase()
            val id = when {
                host == "youtu.be" -> parsed.encodedPathSegments.lastOrNull()
                host == "youtube.com" || host.endsWith(".youtube.com") ->
                    if (parsed.encodedPath == "/watch") parsed.queryParameter("v")
                    else parsed.pathSegments.getOrNull(1)?.takeIf {
                        parsed.pathSegments.firstOrNull() in setOf("shorts", "embed", "live")
                    }
                else -> null
            }
            return id?.takeIf(::isValidVideoId)
        }

        internal fun isValidVideoId(value: String): Boolean =
            value.matches(Regex("^[A-Za-z0-9_-]{11}$"))

        internal fun youtubeUrl(videoId: String): String =
            "https://www.youtube.com/watch?v=$videoId"
    }
}
