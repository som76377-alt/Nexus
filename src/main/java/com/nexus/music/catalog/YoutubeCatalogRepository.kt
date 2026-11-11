package com.nexus.music.catalog

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
 * NewPipe is synchronous and stores its downloader process-wide. Each operation selects its
 * downloader and performs extraction under one serialized critical section.
 */
class YoutubeCatalogRepository(
    private val downloader: OkHttpDownloader = OkHttpDownloader()
) {
    suspend fun search(
        query: String,
        scope: YoutubeSearchScope = YoutubeSearchScope.EVERYTHING
    ): List<CatalogTrack> = withContext(Dispatchers.IO) {
        require(query.trim().isNotEmpty()) { "Search query must not be blank." }
        ExtractorRuntime.withDownloader(downloader) {
            val normalizedQuery = query.trim()
            val service = NewPipe.getService(YOUTUBE_SERVICE_NAME)
            val filters = scope.extractorFilter?.let(::listOf).orEmpty()
            val queryHandler = service.searchQHFactory.fromQuery(
                normalizedQuery,
                filters,
                ""
            )
            val extractor = service.getSearchExtractor(queryHandler)
            extractor.fetchPage()

            SearchInfo.getInfo(extractor).relatedItems
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
    }

    suspend fun resolveAudio(videoUrl: String): ResolvedAudio = withContext(Dispatchers.IO) {
        require(CatalogUrlValidator.isValidHttpsUrl(videoUrl)) {
            "A valid HTTPS video URL is required."
        }
        ExtractorRuntime.withDownloader(downloader) {
            val service = NewPipe.getServiceByUrl(videoUrl)
            val info = StreamInfo.getInfo(service, videoUrl)
            val canonicalSourceUrl = info.url.takeIf(CatalogUrlValidator::isValidHttpsUrl)
                ?: throw IOException("The source returned an invalid video URL.")
            val selected = AudioStreamSelector.bestPlayable(info.audioStreams)
                ?: throw IOException("No directly playable audio stream was returned for this item.")

            ResolvedAudio(
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
    }

    companion object {
        private const val YOUTUBE_SERVICE_NAME = "YouTube"
    }
}
