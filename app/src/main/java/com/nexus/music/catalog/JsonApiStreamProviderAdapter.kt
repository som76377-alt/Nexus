package com.nexus.music.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * JSON API adapters for compatible Piped and Invidious instances.
 *
 * Instance URLs are supplied by configuration; no public instance is treated as permanent.
 * These adapters deliberately resolve only direct audio URLs, never manifests or video streams.
 */
class PipedStreamProviderAdapter(
    endpoints: List<String>,
    client: OkHttpClient = JsonApiStreamProviderAdapter.defaultApiClient(),
    maxInstanceAttempts: Int = JsonApiStreamProviderAdapter.DEFAULT_MAX_INSTANCE_ATTEMPTS_UNVERIFIED,
    callDeadlineMs: Long = JsonApiStreamProviderAdapter.DEFAULT_CALL_DEADLINE_MS_UNVERIFIED,
    nowEpochMs: () -> Long = System::currentTimeMillis,
    monotonicNowMs: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }
) : JsonApiStreamProviderAdapter(
    StreamProvider.PIPED, endpoints, client, maxInstanceAttempts, callDeadlineMs, nowEpochMs, monotonicNowMs
) {
    override fun searchUrl(base: HttpUrl, query: String, scope: YoutubeSearchScope): HttpUrl =
        base.newBuilder()
            .addPathSegments("search")
            .addQueryParameter("q", query)
            .addQueryParameter("filter", scope.toPipedFilter())
            .build()

    private fun YoutubeSearchScope.toPipedFilter(): String = when (this) {
        YoutubeSearchScope.EVERYTHING -> "all"
        YoutubeSearchScope.MUSIC_SONGS -> "music_songs"
        YoutubeSearchScope.VIDEOS -> "videos"
    }

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
            mimeType = selected.optString("mimeType").takeIf(String::isNotBlank),
            requestHeaders = parseSelectedStreamHeaders(selected)
        )
    }
}

class InvidiousStreamProviderAdapter(
    endpoints: List<String>,
    client: OkHttpClient = JsonApiStreamProviderAdapter.defaultApiClient(),
    maxInstanceAttempts: Int = JsonApiStreamProviderAdapter.DEFAULT_MAX_INSTANCE_ATTEMPTS_UNVERIFIED,
    callDeadlineMs: Long = JsonApiStreamProviderAdapter.DEFAULT_CALL_DEADLINE_MS_UNVERIFIED,
    nowEpochMs: () -> Long = System::currentTimeMillis,
    monotonicNowMs: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }
) : JsonApiStreamProviderAdapter(
    StreamProvider.INVIDIOUS, endpoints, client, maxInstanceAttempts, callDeadlineMs, nowEpochMs, monotonicNowMs
) {
    override fun searchUrl(base: HttpUrl, query: String, scope: YoutubeSearchScope): HttpUrl {
        if (scope == YoutubeSearchScope.MUSIC_SONGS) {
            throw ProviderCapabilityException("Invidious does not expose a music-songs search filter.")
        }
        return base.newBuilder()
            .addPathSegments("api/v1/search")
            .addQueryParameter("q", query)
            .apply {
                if (scope == YoutubeSearchScope.VIDEOS) addQueryParameter("type", "video")
            }
            .build()
    }

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
            mimeType = selected.optString("type").takeIf(String::isNotBlank),
            requestHeaders = parseSelectedStreamHeaders(selected)
        )
    }
}

/** Parse safe request headers from the chosen audio format only. */
private fun parseSelectedStreamHeaders(stream: JSONObject): Map<String, String> {
    val raw = stream.optJSONObject("httpHeaders") ?: stream.optJSONObject("headers") ?: return emptyMap()
    val values = buildMap {
        val keys = raw.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = raw.optString(key).takeIf(String::isNotBlank) ?: continue
            put(key, value)
        }
    }
    return sanitizeStreamRequestHeaders(values)
}

/** Shared HTTP, instance failover, and YouTube URL validation for JSON provider APIs. */
abstract class JsonApiStreamProviderAdapter(
    final override val provider: StreamProvider,
    endpoints: List<String>,
    private val client: OkHttpClient,
    private val maxInstanceAttempts: Int,
    private val callDeadlineMs: Long,
    private val nowEpochMs: () -> Long,
    private val monotonicNowMs: () -> Long
) : StreamProviderAdapter {
    private enum class Operation { SEARCH, RESOLVE }

    @Volatile
    private var endpoints: List<HttpUrl> = parseEndpoints(endpoints)

    /** Operation-scoped, process-local cooldowns; no strike counters or persisted health state. */
    private val cooldownUntilByOperationAndEndpoint = ConcurrentHashMap<Pair<Operation, String>, Long>()

    init {
        require(maxInstanceAttempts > 0) { "Maximum instance attempts must be positive." }
        require(callDeadlineMs > 0L) { "Provider call deadline must be positive." }
    }

    /**
     * Replaces the current instance pool after trusted directory discovery.
     * An empty update is ignored so a transient discovery failure cannot erase a working pool.
     */
    fun updateEndpoints(values: List<String>) {
        val parsed = parseEndpoints(values)
        if (parsed.isNotEmpty()) endpoints = parsed
    }

    private fun parseEndpoints(values: List<String>): List<HttpUrl> =
        values.map { raw ->
            raw.trim().trimEnd('/').toHttpUrl().also {
                require(it.isHttps || it.host in LOOPBACK_HOSTS) {
                    "Provider endpoints must use HTTPS unless they target loopback."
                }
            }
        }.distinct()

    final override suspend fun search(
        query: String,
        scope: YoutubeSearchScope
    ): List<CatalogTrack> = withContext(Dispatchers.IO) {
        val normalized = query.trim()
        require(normalized.isNotEmpty()) { "Search query must not be blank." }
        executeAcrossInstances(Operation.SEARCH) { base ->
            parseSearch(get(searchUrl(base, normalized, scope)))
        }
    }

    final override suspend fun resolveAudio(videoUrl: String): ResolvedAudio = withContext(Dispatchers.IO) {
        val videoId = JsonApiStreamProviderAdapter.youtubeId(videoUrl)
            ?: throw IllegalArgumentException("A supported YouTube video URL is required.")
        executeAcrossInstances(Operation.RESOLVE) { base ->
            parseResolved(get(resolveUrl(base, videoId)), videoId)
        }
    }

    protected abstract fun searchUrl(base: HttpUrl, query: String, scope: YoutubeSearchScope): HttpUrl
    protected abstract fun parseSearch(body: String): List<CatalogTrack>
    protected abstract fun resolveUrl(base: HttpUrl, videoId: String): HttpUrl
    protected abstract fun parseResolved(body: String, videoId: String): ResolvedAudio

    private suspend fun get(url: HttpUrl): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (!it.isSuccessful) {
                            throw ProviderHttpException(
                                statusCode = it.code,
                                provider = provider.key,
                                retryAfterMs = parseRetryAfterMillis(it.header("Retry-After"))
                            )
                        }
                        val responseBody = it.body
                            ?: throw IOException("${provider.key} API returned an empty response.")
                        val source = responseBody.source()
                        if (source.request(MAX_API_RESPONSE_BYTES + 1L)) {
                            throw IOException("${provider.key} API response exceeded the size limit.")
                        }
                        source.readUtf8()
                    }
                    if (continuation.isActive) continuation.resume(body)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private suspend fun <T> executeAcrossInstances(
        operation: Operation,
        block: suspend (HttpUrl) -> T
    ): T {
        var lastError: IOException? = null
        val configuredEndpoints = endpoints
        if (configuredEndpoints.isEmpty()) {
            throw IOException("No ${provider.key} instance is configured.")
        }

        val now = nowEpochMs()
        val healthyCandidates = configuredEndpoints.filter { endpoint ->
            (cooldownUntilByOperationAndEndpoint[operation to endpoint.toString()] ?: 0L) <= now
        }
        // If every endpoint is cooling, allow exactly one half-open probe rather than deadlocking
        // the provider forever. A successful response clears that operation's cooldown.
        val candidates = healthyCandidates.ifEmpty { configuredEndpoints.take(1) }
        val deadlineAt = monotonicNowMs() + callDeadlineMs
        var attempted = 0

        for (endpoint in candidates) {
            if (attempted >= maxInstanceAttempts) break
            val remainingMs = deadlineAt - monotonicNowMs()
            if (remainingMs <= 0L) {
                lastError = SocketTimeoutException(
                    "${provider.key} provider call exceeded its ${callDeadlineMs}ms deadline."
                )
                break
            }
            attempted += 1

            try {
                val result = withTimeout(remainingMs) { block(endpoint) }
                cooldownUntilByOperationAndEndpoint.remove(operation to endpoint.toString())
                return result
            } catch (error: CancellationException) {
                if (error !is TimeoutCancellationException) throw error
                // Do not swallow cancellation from the caller or an enclosing timeout.
                currentCoroutineContext().ensureActive()
                lastError = SocketTimeoutException(
                    "${provider.key} provider call exceeded its ${callDeadlineMs}ms deadline."
                )
                break
            } catch (error: ProviderCapabilityException) {
                throw error
            } catch (error: ProviderHttpException) {
                lastError = error
                if (operation == Operation.SEARCH && shouldCooldownSearchInstance(error.statusCode)) {
                    val duration = if (error.statusCode == 429) {
                        error.retryAfterMs ?: DEFAULT_SEARCH_INSTANCE_COOLDOWN_MS
                    } else {
                        DEFAULT_SEARCH_INSTANCE_COOLDOWN_MS
                    }
                    cooldownUntilByOperationAndEndpoint[operation to endpoint.toString()] =
                        nowEpochMs() + duration
                }
            } catch (error: IOException) {
                lastError = error
            } catch (error: org.json.JSONException) {
                lastError = IOException("${provider.key} API returned an invalid response.", error)
            }
        }

        throw lastError ?: SocketTimeoutException(
            "${provider.key} provider call exceeded its ${callDeadlineMs}ms deadline or attempt limit."
        )
    }

    private fun shouldCooldownSearchInstance(statusCode: Int): Boolean =
        statusCode in SEARCH_INSTANCE_COOLDOWN_STATUSES || statusCode == 429

    companion object {
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        private const val USER_AGENT = "Nexus/0.1 (Android)"
        private const val MAX_API_RESPONSE_BYTES = 8_388_608L

        // PROVISIONAL / UNVERIFIED until E1/E2 measurements justify different defaults.
        internal const val DEFAULT_MAX_INSTANCE_ATTEMPTS_UNVERIFIED = 5
        internal const val DEFAULT_CALL_DEADLINE_MS_UNVERIFIED = 15_000L
        private const val DEFAULT_SEARCH_INSTANCE_COOLDOWN_MS = 5 * 60_000L
        private val SEARCH_INSTANCE_COOLDOWN_STATUSES = setOf(400, 401, 403, 404, 405, 410)

        internal fun parseRetryAfterMillis(
            value: String?,
            nowEpochMs: Long = System.currentTimeMillis()
        ): Long? {
            val header = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
            header.toLongOrNull()?.let { seconds ->
                if (seconds < 0L) return null
                return if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
            }
            val position = ParsePosition(0)
            val retryAt = runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("GMT")
                }.parse(header, position)?.time
                    ?.takeIf { position.errorIndex < 0 && position.index == header.length }
            }.getOrNull() ?: return null
            return (retryAt - nowEpochMs).coerceAtLeast(0L)
        }

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
