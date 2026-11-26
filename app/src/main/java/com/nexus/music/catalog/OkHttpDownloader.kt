package com.nexus.music.catalog

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.nexus.music.performance.NexusPerformance
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP transport used by NewPipe Extractor.
 *
 * This is deliberately a transport adapter only: URL parsing and service-specific extraction
 * remain inside NewPipe Extractor. A 429 is surfaced as ReCaptchaException so callers can show
 * a useful retry message instead of silently looping.
 */
class OkHttpDownloader(
    private val client: OkHttpClient = defaultClient()
) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: ExtractorRequest): ExtractorResponse {
        val method = request.httpMethod().uppercase()
        val payload = request.dataToSend()
        val body = when {
            method == "GET" || method == "HEAD" -> null
            payload != null -> payload.toRequestBody(null)
            method == "POST" || method == "PUT" || method == "PATCH" ->
                ByteArray(0).toRequestBody(null)
            else -> null
        }

        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(method, body)
            .header("User-Agent", USER_AGENT)

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }

        val tracing = NexusPerformance.isEnabled()
        val endpointHost = if (tracing) {
            runCatching { request.url().toHttpUrl().host }.getOrDefault("unknown")
        } else ""
        val operationTrace = if (tracing) ExtractorRuntime.currentTrace() else null
        val standaloneTrace = if (tracing && operationTrace == null) {
            NexusPerformance.begin(
                "newpipe_http",
                foreground = NexusPerformance.isForeground(),
                detail = "host=" + endpointHost + " method=" + method
            )
        } else null
        val requestStartedAt = if (tracing) NexusPerformance.nowMs() else 0L
        if (operationTrace != null) {
            NexusPerformance.event(operationTrace, "newpipe_http_begin",
                "host=" + endpointHost + " method=" + method)
        }
        var responseCode: Int? = null
        var failure: String? = null
        try {
            client.newCall(builder.build()).execute().use { response ->
                responseCode = response.code
                if (response.code == HTTP_TOO_MANY_REQUESTS) {
                    throw ReCaptchaException(
                        "The source temporarily rejected the request (HTTP 429). Retry later.",
                        request.url()
                    )
                }

                return ExtractorResponse(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    response.body?.string().orEmpty(),
                    response.request.url.toString()
                )
            }
        } catch (error: IOException) {
            failure = error.javaClass.simpleName
            throw error
        } finally {
            if (tracing) {
                val detail = "host=" + endpointHost + " method=" + method +
                    " duration_ms=" + (NexusPerformance.nowMs() - requestStartedAt) +
                    " http_code=" + (responseCode?.toString() ?: "none") +
                    " failure=" + (failure ?: "none")
                if (operationTrace != null) {
                    NexusPerformance.event(operationTrace, "newpipe_http_complete", detail)
                } else {
                    NexusPerformance.finish(standaloneTrace!!, detail)
                }
            }
        }
    }

    companion object {
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 Nexus/0.1"

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(false)
                .build()
    }
}
