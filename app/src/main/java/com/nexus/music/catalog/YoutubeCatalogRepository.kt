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
 * NewPipe Extractor is synchronous, so all network work is moved to Dispatchers.IO.
 */
class YoutubeCatalogRepository(
    downloader: OkHttpDownloader = OkHttpDownloader()
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
                val url = item.url?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                CatalogTrack(
                    url = url,
                    title = item.name.orEmpty(),
                    artist = item.uploaderName?.takeIf(String::isNotBlank),
                    thumbnailUrl = item.thumbnails.firstOrNull()?.url,
                    durationSeconds = item.duration.takeIf { it >= 0L },
                    viewCount = item.viewCount.takeIf { it >= 0L }
                )
            }
    }

    suspend fun resolveAudio(videoUrl: String): ResolvedAudio = withContext(Dispatchers.IO) {
        require(videoUrl.startsWith("https://") || videoUrl.startsWith("http://")) {
            "A valid video URL is required."
        }

        val service = NewPipe.getServiceByUrl(videoUrl)
        val info = StreamInfo.getInfo(service, videoUrl)
        val selected = AudioStreamSelector.bestPlayable(info.audioStreams)
            ?: throw IOException("No directly playable audio stream was returned for this item.")

        ResolvedAudio(
            sourceUrl = info.url,
            title = info.name,
            artist = info.uploaderName?.takeIf(String::isNotBlank),
            thumbnailUrl = info.thumbnails.firstOrNull()?.url,
            durationSeconds = info.duration.takeIf { it >= 0L },
            audioUrl = selected.content,
            averageBitrate = selected.averageBitrate.takeIf { it > 0 }
        )
    }

    companion object {
        private const val YOUTUBE_SERVICE_NAME = "YouTube"
    }
}
