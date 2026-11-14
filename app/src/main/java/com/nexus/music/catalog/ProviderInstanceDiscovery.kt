package com.nexus.music.catalog

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Refreshes provider endpoints from their maintainers' published instance directories.
 * Only HTTPS API endpoints are accepted; cached endpoints remain usable when discovery is offline.
 */
internal class ProviderInstanceDiscovery(
    context: Context,
    private val client: OkHttpClient = defaultClient(),
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun cachedEndpoints(provider: StreamProvider): List<String> =
        preferences.getString(endpointKey(provider), null)
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            ?.toList()
            .orEmpty()

    fun lastDiscoveryStatus(provider: StreamProvider): String =
        preferences.getString(statusKey(provider), "not-run") ?: "not-run"

    suspend fun refreshIfStale(
        onUpdated: (StreamProvider, List<String>) -> Unit
    ) = withContext(Dispatchers.IO) {
        listOf(StreamProvider.PIPED, StreamProvider.INVIDIOUS).forEach { provider ->
            val lastRefresh = preferences.getLong(refreshKey(provider), 0L)
            if (nowEpochMs() - lastRefresh < REFRESH_INTERVAL_MS) return@forEach

            try {
                val discovered = when (provider) {
                    StreamProvider.PIPED -> parsePipedInstances(fetchDirectory(client, PIPED_DIRECTORY_URL))
                    StreamProvider.INVIDIOUS -> discoverInvidiousInstances(client)
                    StreamProvider.NEWPIPE -> emptyList()
                }
                if (discovered.isNotEmpty()) {
                    preferences.edit()
                        .putString(endpointKey(provider), discovered.joinToString("\n"))
                        .putLong(refreshKey(provider), nowEpochMs())
                        .putString(statusKey(provider), "ok:" + discovered.size)
                        .apply()
                    onUpdated(provider, discovered)
                } else {
                    preferences.edit()
                        .putString(statusKey(provider), "empty-directory")
                        .apply()
                }
            } catch (error: IOException) {
                preferences.edit()
                    .putString(statusKey(provider), "error:" + error.message.orEmpty().take(100))
                    .apply()
            } catch (error: org.json.JSONException) {
                preferences.edit()
                    .putString(statusKey(provider), "invalid-json:" + error.message.orEmpty().take(100))
                    .apply()
            }
        }
    }

    internal companion object {
        private const val PREFERENCES_NAME = "nexus_provider_instance_directory"
        private const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val PIPED_DIRECTORY_URL =
            "https://raw.githubusercontent.com/TeamPiped/documentation/main/content/docs/public-instances/index.md"
        private const val INVIDIOUS_DIRECTORY_URL =
            "https://api.invidious.io/instances.json?sort_by=health"
        private const val INVIDIOUS_DOCUMENTATION_URL =
            "https://raw.githubusercontent.com/iv-org/documentation/master/docs/instances.md"
        private const val MAX_ENDPOINTS = 20
        private const val MAX_DIRECTORY_BYTES = 1_048_576L

        internal suspend fun fetchDirectory(client: OkHttpClient, url: String): String =
            suspendCancellableCoroutine { continuation ->
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json, text/markdown, text/plain")
                    .header("User-Agent", "Nexus/0.1 (Android; provider discovery)")
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
                                    throw IOException("Provider directory returned HTTP ${it.code}.")
                                }
                                val responseBody = it.body
                                    ?: throw IOException("Provider directory returned an empty response.")
                                val source = responseBody.source()
                                if (source.request(MAX_DIRECTORY_BYTES + 1L)) {
                                    throw IOException("Provider directory response exceeded the size limit.")
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

        internal fun parsePipedInstances(markdown: String): List<String> {
            val endpoints = LinkedHashSet<String>()
            markdown.lineSequence()
                .filter { it.contains('|') }
                .forEach { line ->
                    val cells = line.trim().trim('|').split('|').map(String::trim)
                    val apiCell = cells.getOrNull(1).orEmpty()
                    ENDPOINT_PATTERN.findAll(apiCell).forEach { match ->
                        normalizeEndpoint(match.value)?.let(endpoints::add)
                    }
                }
            return endpoints.take(MAX_ENDPOINTS)
        }

        private suspend fun discoverInvidiousInstances(client: OkHttpClient): List<String> {
            val endpoints = LinkedHashSet<String>()
            var lastError: Exception? = null

            try {
                endpoints.addAll(parseInvidiousInstances(fetchDirectory(client, INVIDIOUS_DIRECTORY_URL)))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                lastError = error
            } catch (error: org.json.JSONException) {
                lastError = error
            }

            try {
                endpoints.addAll(
                    parseInvidiousDocumentation(
                        fetchDirectory(client, INVIDIOUS_DOCUMENTATION_URL)
                    )
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                lastError = lastError ?: error
            }

            if (endpoints.isEmpty()) {
                throw (lastError as? IOException
                    ?: IOException("No official Invidious API instances were discovered.", lastError))
            }
            return endpoints.take(MAX_ENDPOINTS)
        }

        internal fun parseInvidiousDocumentation(markdown: String): List<String> {
            val endpoints = LinkedHashSet<String>()
            var inPublicList = false
            markdown.lineSequence().forEach { line ->
                if (line.startsWith("## List of public Invidious Instances")) {
                    inPublicList = true
                    return@forEach
                }
                if (inPublicList && line.trimStart().startsWith("###")) {
                    inPublicList = false
                }
                if (!inPublicList) return@forEach

                val url = Regex("""^\s*\*\s*\[[^\]]+\]\((https://[^)]+)\)""")
                    .find(line)?.groupValues?.getOrNull(1) ?: return@forEach
                normalizeEndpoint(url)?.let(endpoints::add)
            }
            return endpoints.take(MAX_ENDPOINTS)
        }

        internal fun parseInvidiousInstances(json: String): List<String> {
            val instances = JSONArray(json)
            val endpoints = LinkedHashSet<String>()
            for (index in 0 until instances.length()) {
                val entry = instances.optJSONArray(index) ?: continue
                val metadata = entry.optJSONObject(1) ?: continue
                if (!metadata.optBoolean("api", false)) continue
                val url = metadata.optString("uri").takeIf(String::isNotBlank) ?: continue
                normalizeEndpoint(url)?.let(endpoints::add)
                if (endpoints.size >= MAX_ENDPOINTS) break
            }
            return endpoints.toList()
        }

        private val ENDPOINT_PATTERN = Regex("https://[^\\s|)<>]+", RegexOption.IGNORE_CASE)

        internal fun normalizeEndpoint(value: String): String? {
            val normalized = value.trim().trimEnd('.', '/', ',', ';')
            val uri = runCatching { java.net.URI(normalized) }.getOrNull() ?: return null
            if (normalized.toHttpUrlOrNull() == null) return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
                return null
            }
            return normalized.trimEnd('/')
        }

        private fun endpointKey(provider: StreamProvider) = "${provider.key}.endpoints"
        private fun refreshKey(provider: StreamProvider) = "${provider.key}.refreshed"
        private fun statusKey(provider: StreamProvider) = "${provider.key}.discovery_status"

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .callTimeout(18, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(false)
                .build()
    }

    private fun endpointKey(provider: StreamProvider) = "${provider.key}.endpoints"
    private fun refreshKey(provider: StreamProvider) = "${provider.key}.refreshed"
    private fun statusKey(provider: StreamProvider) = "${provider.key}.discovery_status"
}
