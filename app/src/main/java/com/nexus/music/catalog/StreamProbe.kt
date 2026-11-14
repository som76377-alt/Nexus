package com.nexus.music.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Performs a bounded ranged GET before a resolved stream is handed to Media3.
 * This catches dead/blocked/expired provider URLs while the resolver can still fail over.
 */
internal class StreamProbe(
    private val client: OkHttpClient = defaultClient(),
    private val allowHttpForTests: Boolean = false
) {
    suspend fun validate(audio: ResolvedAudio): ResolvedAudio = withContext(Dispatchers.IO) {
        if (!CatalogUrlValidator.isValidHttpsUrl(audio.audioUrl) &&
            !(allowHttpForTests && isLoopbackHttpUrl(audio.audioUrl))
        ) {
            throw IOException("The provider returned an invalid HTTPS audio URL.")
        }

        URI(audio.audioUrl).host?.let(StreamAddressFamily::clear)

        val request = Request.Builder()
            .url(audio.audioUrl)
            .header("Range", "bytes=0-0")
            .header("Accept", "audio/*, application/octet-stream;q=0.9, */*;q=0.1")
            .header("User-Agent", USER_AGENT)
            .apply {
                sanitizeStreamRequestHeaders(audio.requestHeaders).forEach { (name, value) ->
                    header(name, value)
                }
            }
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.request.url.isHttps &&
                !(allowHttpForTests && isLoopbackHost(response.request.url.host))
            ) {
                throw IOException("The stream probe was redirected to an insecure URL.")
            }
            if (response.code != 200 && response.code != 206) {
                throw ProviderHttpException(
                    statusCode = response.code,
                    provider = "${audio.provider.key} audio stream",
                    retryAfterMs = JsonApiStreamProviderAdapter.parseRetryAfterMillis(
                        response.header("Retry-After")
                    )
                )
            }

            val contentType = response.header("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
            if (
                contentType != null &&
                !contentType.startsWith("audio/") &&
                contentType != "application/octet-stream" &&
                contentType != "binary/octet-stream"
            ) {
                throw IOException("The provider URL did not return an audio stream.")
            }
            if (response.body?.contentLength() == 0L) {
                throw IOException("The provider returned an empty audio stream.")
            }
        }
        audio
    }

    private fun isLoopbackHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme.equals("http", ignoreCase = true) && isLoopbackHost(uri.host.orEmpty())
    }

    private fun isLoopbackHost(host: String): Boolean =
        host.equals("localhost", ignoreCase = true) ||
            host == "127.0.0.1" || host == "::1"

    private companion object {
        private const val USER_AGENT = "Nexus/0.1 (Android; stream probe)"

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .dns(StreamAddressFamily.dns)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(7, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(false)
                .build()
    }
}
