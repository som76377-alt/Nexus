package com.nexus.music.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException

/**
 * Search and stream-resolution boundary for the app.
 *
 * NewPipe remains primary. If it fails, Nexus tries Piped (server-side extraction) and then
 * Invidious (a separate extraction implementation). The primary circuit-breaker automatically
 * probes NewPipe again after a short cooldown and returns to it when healthy.
 */
class YoutubeCatalogRepository internal constructor(
    downloader: OkHttpDownloader,
    private val pipedFallback: PipedFallbackExtractor,
    private val invidiousFallback: InvidiousFallbackExtractor,
    private val failoverPolicy: ExtractorFailoverPolicy
) {
    private val providerStatus = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Latest per-provider result, safe to read from the UI thread. */
    fun diagnosticsSummary(): String = listOf("NewPipe", "Piped", "Invidious")
        .joinToString("\n") { provider -> provider + ": " + (providerStatus[provider] ?: "Not tried yet") }

    private inline fun <T> tracked(provider: String, block: () -> T): T {
        return try {
            block().also { providerStatus[provider] = "OK" }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            providerStatus[provider] =
                (error.javaClass.simpleName + ": " + (error.message ?: "request failed")).take(220)
            throw error
        }
    }
    constructor(downloader: OkHttpDownloader = OkHttpDownloader()) :
        this(
            downloader,
            PipedFallbackExtractor(),
            InvidiousFallbackExtractor(),
            ExtractorFailoverPolicy()
        )

    init {
        ExtractorRuntime.initialize(downloader)
    }

    suspend fun search(
        query: String,
        scope: YoutubeSearchScope = YoutubeSearchScope.EVERYTHING
    ): List<CatalogTrack> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotEmpty()) { "Search query must not be blank." }

        var primaryFailure: Exception? = null
        var primaryReturnedEmpty = false
        if (failoverPolicy.shouldTryPrimary()) {
            try {
                val results = searchWithNewPipe(normalizedQuery, scope)
                if (results.isNotEmpty()) {
                    failoverPolicy.primarySucceeded()
                    return@withContext results
                }
                primaryReturnedEmpty = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                primaryFailure = error
                failoverPolicy.primaryFailed()
            }
        }

        try {
            val results = searchWithFallbacks(normalizedQuery, scope)
            if (primaryReturnedEmpty) {
                if (results.isNotEmpty()) failoverPolicy.primaryFailed()
                else failoverPolicy.primarySucceeded()
            }
            results
        } catch (fallbackError: Exception) {
            if (fallbackError is CancellationException) throw fallbackError
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(fallbackError)
                throw primaryFailure
            }
            // If all fallbacks are unavailable while the circuit is open, probe primary once.
            try {
                val results = searchWithNewPipe(normalizedQuery, scope)
                failoverPolicy.primarySucceeded()
                results
            } catch (primaryError: Exception) {
                if (primaryError is CancellationException) throw primaryError
                failoverPolicy.primaryFailed()
                primaryError.addSuppressed(fallbackError)
                throw primaryError
            }
        }
    }

    suspend fun resolveAudio(videoUrl: String): ResolvedAudio = withContext(Dispatchers.IO) {
        require(CatalogUrlValidator.isValidHttpsUrl(videoUrl)) {
            "A valid HTTPS video URL is required."
        }

        var primaryFailure: Exception? = null
        if (failoverPolicy.shouldTryPrimary()) {
            try {
                val resolved = resolveWithNewPipe(videoUrl)
                failoverPolicy.primarySucceeded()
                return@withContext resolved
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                primaryFailure = error
                failoverPolicy.primaryFailed()
            }
        }

        try {
            resolveWithFallbacks(videoUrl)
        } catch (fallbackError: Exception) {
            if (fallbackError is CancellationException) throw fallbackError
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(fallbackError)
                throw primaryFailure
            }
            // If both fallback routes are down, probe primary once before failing the request.
            try {
                val resolved = resolveWithNewPipe(videoUrl)
                failoverPolicy.primarySucceeded()
                resolved
            } catch (primaryError: Exception) {
                if (primaryError is CancellationException) throw primaryError
                failoverPolicy.primaryFailed()
                primaryError.addSuppressed(fallbackError)
                throw primaryError
            }
        }
    }

    private fun searchWithFallbacks(
        query: String,
        scope: YoutubeSearchScope
    ): List<CatalogTrack> {
        var successfulEmptyResult = false
        val failures = mutableListOf<Exception>()
        listOf<(String, YoutubeSearchScope) -> List<CatalogTrack>>(
            { q, s -> tracked("Piped") { pipedFallback.search(q, s) } },
            { q, s -> tracked("Invidious") { invidiousFallback.search(q, s) } }
        ).forEach { provider ->
            try {
                val results = provider(query, scope)
                if (results.isNotEmpty()) return results
                successfulEmptyResult = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failures += error
            }
        }
        if (successfulEmptyResult) return emptyList()
        throw fallbackFailure("Search", failures)
    }

    private fun resolveWithFallbacks(videoUrl: String): ResolvedAudio {
        val failures = mutableListOf<Exception>()
        listOf<(String) -> ResolvedAudio>(
            { url -> tracked("Piped") { pipedFallback.resolveAudio(url) } },
            { url -> tracked("Invidious") { invidiousFallback.resolveAudio(url) } }
        ).forEach { provider ->
            try {
                return provider(videoUrl)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failures += error
            }
        }
        throw fallbackFailure("Audio resolution", failures)
    }

    private fun fallbackFailure(operation: String, failures: List<Exception>): IOException {
        val failure = IOException("All fallback extractors failed during $operation.")
        failures.forEach(failure::addSuppressed)
        return failure
    }

    private fun searchWithNewPipe(query: String, scope: YoutubeSearchScope): List<CatalogTrack> =
        tracked("NewPipe") {
        val service = NewPipe.getService(YOUTUBE_SERVICE_NAME)
        val filters = scope.extractorFilter?.let(::listOf).orEmpty()
        val queryHandler = service.searchQHFactory.fromQuery(query, filters, "")
        val extractor = service.getSearchExtractor(queryHandler)
        extractor.fetchPage()

        return SearchInfo.getInfo(extractor).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val url = item.url?.takeIf(CatalogUrlValidator::isValidHttpsUrl)
                    ?: return@mapNotNull null
                CatalogTrack(
                    url = url,
                    title = item.name.orEmpty(),
                    artist = item.uploaderName?.takeIf(String::isNotBlank),
                    thumbnailUrl = item.thumbnails.firstOrNull()?.url
                        ?.takeIf(CatalogUrlValidator::isValidHttpsUrl),
                    durationSeconds = item.duration.takeIf { it >= 0L },
                    viewCount = item.viewCount.takeIf { it >= 0L }
                )
            }
    }

    private fun resolveWithNewPipe(videoUrl: String): ResolvedAudio =
        tracked("NewPipe") {
        val service = NewPipe.getServiceByUrl(videoUrl)
        val info = StreamInfo.getInfo(service, videoUrl)
        val canonicalSourceUrl = info.url.takeIf(CatalogUrlValidator::isValidHttpsUrl)
            ?: throw IOException("The source returned an invalid video URL.")
        val selected = AudioStreamSelector.bestPlayable(info.audioStreams)
            ?: throw IOException("No directly playable audio stream was returned for this item.")

        return ResolvedAudio(
            sourceUrl = canonicalSourceUrl,
            title = info.name,
            artist = info.uploaderName?.takeIf(String::isNotBlank),
            thumbnailUrl = info.thumbnails.firstOrNull()?.url
                ?.takeIf(CatalogUrlValidator::isValidHttpsUrl),
            durationSeconds = info.duration.takeIf { it >= 0L },
            audioUrl = selected.content,
            averageBitrate = selected.averageBitrate.takeIf { it > 0 },
            mimeType = selected.format?.mimeType
        )
    }

    companion object {
        private const val YOUTUBE_SERVICE_NAME = "YouTube"
    }
}
