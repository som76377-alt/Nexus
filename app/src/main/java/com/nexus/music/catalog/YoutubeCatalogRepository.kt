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
 * NewPipe remains the primary extractor. When it fails, Piped provides an independent
 * server-side extraction route. A short circuit-breaker cooldown avoids repeatedly hitting a
 * failing primary route, and the primary is automatically retried after the cooldown.
 */
class YoutubeCatalogRepository(
    downloader: OkHttpDownloader = OkHttpDownloader(),
    private val fallback: PipedFallbackExtractor = PipedFallbackExtractor(),
    private val failoverPolicy: ExtractorFailoverPolicy = ExtractorFailoverPolicy()
) {
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
        if (failoverPolicy.shouldTryPrimary()) {
            try {
                val results = searchWithNewPipe(normalizedQuery, scope)
                if (results.isNotEmpty()) {
                    failoverPolicy.primarySucceeded()
                    return@withContext results
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                primaryFailure = error
                failoverPolicy.primaryFailed()
            }
        }

        try {
            // An empty result is still a valid search response; the fallback gets a chance
            // to recover results without incorrectly opening the primary circuit.
            fallback.search(normalizedQuery, scope)
        } catch (fallbackError: Exception) {
            if (fallbackError is CancellationException) throw fallbackError
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(fallbackError)
                throw primaryFailure
            }
            // The primary circuit was open. If fallback is also unavailable, make one last
            // primary attempt so a recovered primary can restore service immediately.
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
            fallback.resolveAudio(videoUrl)
        } catch (fallbackError: Exception) {
            if (fallbackError is CancellationException) throw fallbackError
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(fallbackError)
                throw primaryFailure
            }
            // If the fallback is down while the primary circuit is open, probe primary once.
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

    private fun searchWithNewPipe(query: String, scope: YoutubeSearchScope): List<CatalogTrack> {
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

    private fun resolveWithNewPipe(videoUrl: String): ResolvedAudio {
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
