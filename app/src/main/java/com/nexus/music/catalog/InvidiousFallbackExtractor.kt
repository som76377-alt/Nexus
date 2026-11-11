package com.nexus.music.catalog

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Independent extractor fallback backed by the vetted public Invidious API instances.
 * Unlike Piped, Invidious uses a separate extraction implementation.
 */
internal class InvidiousFallbackExtractor(
    private val client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val instanceUrls: List<String>? = null
) {
    private val unhealthyUntil = mutableMapOf<String, Long>()
    private val instanceLock = Any()
    private val nextInstance = AtomicInteger()
    private var cachedInstances: List<String> = emptyList()
    private var instancesLoadedAt = 0L
    private val discoveryClient by lazy {
        client.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

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
            val url = "$base/api/v1/videos/$videoId".toHttpUrl().newBuilder()
                .addQueryParameter("region", "IN")
                .addQueryParameter("local", "true")
                .build()
            val json = getObject(url.toString(), base)
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
        val candidates = instances()
        if (candidates.isEmpty()) {
            throw IOException("No API-enabled HTTPS Invidious instances are available.")
        }
        val failures = mutableListOf<Exception>()
        val start = Math.floorMod(nextInstance.getAndIncrement(), candidates.size)
        for (offset in candidates.indices) {
            val base = candidates[(start + offset) % candidates.size]
            if (isCoolingDown(base)) continue
            try {
                return block(base)
            } catch (error: Exception) {
                markUnhealthy(base)
                failures += error
            }
        }
        val detail = failures.take(4).joinToString("; ") {
            it.message ?: it.javaClass.simpleName
        }
        val failure = IOException("All API-enabled Invidious fallback instances failed: $detail")
        failures.forEach(failure::addSuppressed)
        throw failure
    }

    private fun instances(): List<String> = synchronized(instanceLock) {
        if (instanceUrls != null) {
            return@synchronized instanceUrls.mapNotNull(::normalizeHttpsInstance).distinct()
        }

        val now = clock()
        if (cachedInstances.isNotEmpty() && now - instancesLoadedAt < INSTANCE_CACHE_MS) {
            return@synchronized cachedInstances
        }

        val discovered = discoverInstances()
        cachedInstances = (discovered + DEFAULT_INSTANCES)
            .mapNotNull(::normalizeHttpsInstance)
            .distinct()
            .take(MAX_INSTANCES)
        instancesLoadedAt = now
        cachedInstances
    }

    private fun discoverInstances(): List<String> {
        val request = Request.Builder()
            .url(INSTANCE_DIRECTORY_URL)
            .header("Accept", "application/json")
            .header("User-Agent", "Nexus Android")
            .build()
        return try {
            discoveryClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                parseMaintainedInstances(response.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeHttpsInstance(value: String): String? {
        val candidate = value.trim().trimEnd('/')
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        return candidate
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
        private const val INSTANCE_DIRECTORY_URL =
            "https://api.invidious.io/instances.json?pretty=1&sort_by=type,users"
        // Last-known official instance with API access enabled; refreshed directory takes priority.
        private val DEFAULT_INSTANCES = listOf("https://yt.chocolatemoo53.com")
        private const val INSTANCE_CACHE_MS = 30 * 60 * 1000L
        private const val INSTANCE_COOLDOWN_MS = 2 * 60 * 1000L
        private const val MAX_INSTANCES = 8
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        internal fun parseMaintainedInstances(body: String): List<String> {
            val entries = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
            return (0 until entries.length())
                .mapNotNull { index ->
                    val entry = entries.optJSONArray(index) ?: return@mapNotNull null
                    val config = entry.optJSONObject(1) ?: return@mapNotNull null
                    if (!config.optBoolean("api") ||
                        !config.optString("type").equals("https", ignoreCase = true)
                    ) {
                        return@mapNotNull null
                    }
                    val monitor = config.optJSONObject("monitor")
                    if (monitor?.optBoolean("down", false) == true) {
                        return@mapNotNull null
                    }
                    val uri = config.optString("uri")
                    val normalized = normalizeMaintainedHttpsUri(uri) ?: return@mapNotNull null
                    val uptime = monitor?.optDouble("uptime", 0.0) ?: 0.0
                    normalized to uptime
                }
                .sortedByDescending { it.second }
                .map { it.first }
                .distinct()
                .take(MAX_INSTANCES)
        }

        private fun normalizeMaintainedHttpsUri(value: String): String? {
            val candidate = value.trim().trimEnd('/')
            val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
                return null
            }
            return candidate
        }

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(11, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
