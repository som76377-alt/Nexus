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
 * Server-side extraction fallback. Piped runs its own extractor and exposes normalized
 * search/stream responses, independent of the on-device NewPipe request path.
 */
internal class PipedFallbackExtractor(
    private val client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val instanceUrls: List<String>? = null
) {
    private val instanceLock = Any()
    private var cachedInstances: List<String> = emptyList()
    private var instancesLoadedAt = 0L
    private val unhealthyUntil = mutableMapOf<String, Long>()
    private val nextInstance = AtomicInteger()
    private val discoveryClient by lazy {
        client.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

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
            val proxyUrl = json.optString("proxyUrl")
                .takeIf(CatalogUrlValidator::isValidHttpsUrl)
            val audioUrl = proxyUrl?.let { rewriteGoogleVideoUrl(selected.second, it) }
                ?: selected.second
            ResolvedAudio(
                sourceUrl = youtubeUrl(videoId),
                title = json.optString("title").takeIf(String::isNotBlank)
                    ?: throw IOException("The fallback returned no track title."),
                artist = json.optString("uploader").takeIf(String::isNotBlank),
                thumbnailUrl = json.optString("thumbnailUrl")
                    .takeIf(CatalogUrlValidator::isValidHttpsUrl),
                durationSeconds = json.optLong("duration", -1L).takeIf { it >= 0L },
                audioUrl = audioUrl,
                averageBitrate = stream.optInt("bitrate", 0).takeIf { it > 0 },
                mimeType = selected.third
            )
        }
    }

    private fun <T> withInstance(block: (String) -> T): T {
        val candidates = instances()
        if (candidates.isEmpty()) {
            throw IOException("No Piped fallback instances are configured.")
        }

        val failures = mutableListOf<Exception>()
        val start = Math.floorMod(nextInstance.getAndIncrement(), candidates.size)
        var attempted = 0
        for (offset in candidates.indices) {
            if (attempted >= MAX_INSTANCES_PER_REQUEST) break
            val base = candidates[(start + offset) % candidates.size]
            if (isCoolingDown(base)) continue
            attempted++
            try {
                return block(base)
            } catch (error: Exception) {
                markUnhealthy(base)
                failures += error
            }
        }

        if (failures.isEmpty()) {
            throw IOException("All available Piped fallback instances are cooling down.")
        }
        val detail = failures.take(4).joinToString("; ") {
            it.message ?: it.javaClass.simpleName
        }
        val failure = IOException("Piped fallback failed on $attempted instance(s): $detail")
        failures.forEach(failure::addSuppressed)
        throw failure
    }

    /**
     * Piped's frontend rewrites Googlevideo media URLs through the instance's proxyUrl.
     * Direct Googlevideo URLs can return 403 to clients even after stream resolution succeeds.
     */
    private fun rewriteGoogleVideoUrl(mediaUrl: String, proxyUrl: String): String {
        val media = runCatching { mediaUrl.toHttpUrl() }.getOrNull() ?: return mediaUrl
        val proxy = runCatching { proxyUrl.toHttpUrl() }.getOrNull() ?: return mediaUrl
        if (!media.host.endsWith(".googlevideo.com", ignoreCase = true)) return mediaUrl

        return media.newBuilder()
            .scheme(proxy.scheme)
            .host(proxy.host)
            .port(proxy.port)
            .encodedPath(proxy.encodedPath.trimEnd('/') + media.encodedPath)
            .setQueryParameter("host", media.host)
            .build()
            .toString()
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
        if (instanceUrls != null) {
            return@synchronized instanceUrls.mapNotNull(::normalizeInstanceUrl).distinct()
        }

        val now = clock()
        if (cachedInstances.isNotEmpty() && now - instancesLoadedAt < INSTANCE_CACHE_MS) {
            return@synchronized cachedInstances
        }

        val discovered = discoverInstances()
        cachedInstances = (discovered + TRUSTED_INSTANCES)
            .mapNotNull(::normalizeInstanceUrl)
            .distinct()
            .take(MAX_DISCOVERED_INSTANCES)
        instancesLoadedAt = now
        cachedInstances
    }

    private fun discoverInstances(): List<String> {
        val request = Request.Builder()
            .url(INSTANCE_DIRECTORY_URL)
            .header("Accept", "text/plain")
            .header("User-Agent", "Nexus Android")
            .build()
        return try {
            discoveryClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                parseInstanceDirectory(response.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeInstanceUrl(value: String): String? {
        val candidate = value.trim().trimEnd('/')
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) return null
        return candidate
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
        private const val INSTANCE_DIRECTORY_URL =
            "https://raw.githubusercontent.com/wiki/TeamPiped/Piped-Frontend/Instances.md"
        private val TRUSTED_INSTANCES = listOf(
            "https://pipedapi.in.projectsegfau.lt",
            "https://pipedapi.syncpundit.io",
            "https://api-piped.mha.fi",
            "https://pipedapi.tokhmi.xyz",
            "https://pipedapi.moomoo.me",
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.rivo.lol",
            "https://pipedapi.leptons.xyz",
            "https://piped-api.lunar.icu",
            "https://api.piped.privacydev.net",
            "https://pipedapi.smnz.de",
            "https://pipedapi.qdi.fi"
        )
        private const val INSTANCE_CACHE_MS = 30 * 60 * 1000L
        private const val INSTANCE_COOLDOWN_MS = 3 * 60 * 1000L
        private const val MAX_DISCOVERED_INSTANCES = 32
        private const val MAX_INSTANCES_PER_REQUEST = 4
        private val DIRECTORY_URL = Regex("""https://[^\s|<>()]+""")
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private val AUDIO_FORMATS = setOf(
            "M4A", "MP3", "MP2", "OPUS", "OGG", "WEBMA", "WEBMA_OPUS", "FLAC", "ALAC", "WAV"
        )

        internal fun parseInstanceDirectory(body: String): List<String> =
            body.lineSequence()
                .mapNotNull { line ->
                    val columns = line.trim().trim('|').split('|')
                    val apiCell = columns.getOrNull(1) ?: return@mapNotNull null
                    val candidate = DIRECTORY_URL.find(apiCell)?.value
                        ?.trimEnd(')', ',', ';', '`')
                        ?: return@mapNotNull null
                    val uri = runCatching { URI(candidate) }.getOrNull() ?: return@mapNotNull null
                    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
                        null
                    } else {
                        candidate.trimEnd('/')
                    }
                }
                .distinct()
                .take(MAX_DISCOVERED_INSTANCES)
                .toList()

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
