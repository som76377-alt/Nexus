package com.nexus.music.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.exceptions.ParsingException

/**
 * Provider boundary shared by catalogue search and playback resolution.
 * Implementations must return canonical source URLs and directly playable HTTPS audio URLs.
 */
interface StreamProviderAdapter {
    val provider: StreamProvider

    suspend fun search(
        query: String,
        scope: YoutubeSearchScope = YoutubeSearchScope.EVERYTHING
    ): List<CatalogTrack>

    suspend fun resolveAudio(videoUrl: String): ResolvedAudio
}

/**
 * Existing NewPipe implementation. Extraction remains serialized because NewPipe's downloader
 * is process-wide; this preserves the current working path while other adapters are added.
 */
class NewPipeStreamProviderAdapter(
    private val downloader: OkHttpDownloader = OkHttpDownloader()
) : StreamProviderAdapter {
    override val provider: StreamProvider = StreamProvider.NEWPIPE

    override suspend fun search(
        query: String,
        scope: YoutubeSearchScope
    ): List<CatalogTrack> = withContext(Dispatchers.IO) {
        require(query.trim().isNotEmpty()) { "Search query must not be blank." }
        try {
            ExtractorRuntime.withDownloader(downloader) {
                val normalizedQuery = query.trim()
                val service = NewPipe.getService(YOUTUBE_SERVICE_NAME)
                val filters = scope.extractorFilter?.let(::listOf).orEmpty()
                val queryHandler = service.searchQHFactory.fromQuery(normalizedQuery, filters, "")
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
        } catch (error: ParsingException) {
            throw ProviderResponseException("NewPipe returned an invalid search response.", error)
        }
    }

    override suspend fun resolveAudio(videoUrl: String): ResolvedAudio = withContext(Dispatchers.IO) {
        require(CatalogUrlValidator.isValidHttpsUrl(videoUrl)) {
            "A valid HTTPS video URL is required."
        }
        try {
            ExtractorRuntime.withDownloader(downloader) {
                val service = NewPipe.getServiceByUrl(videoUrl)
                val info = StreamInfo.getInfo(service, videoUrl)
                val canonicalSourceUrl = info.url.takeIf(CatalogUrlValidator::isValidHttpsUrl)
                    ?: throw ProviderResponseException("The source returned an invalid video URL.")
                val selected = AudioStreamSelector.bestPlayable(info.audioStreams)
                    ?: throw ProviderContentException("No directly playable audio stream was returned for this item.")

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
        } catch (error: ParsingException) {
            throw ProviderResponseException("NewPipe returned an invalid stream response.", error)
        }
    }

    private companion object {
        const val YOUTUBE_SERVICE_NAME = "YouTube"
    }
}
