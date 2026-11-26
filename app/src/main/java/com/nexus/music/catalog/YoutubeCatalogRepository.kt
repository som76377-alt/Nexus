package com.nexus.music.catalog

import kotlinx.coroutines.withContext

/**
 * Search and stream-resolution facade used by the app and playback service.
 *
 * The default adapter is the existing NewPipe implementation. Keeping this facade stable lets
 * the resolver chain be introduced without changing current UI or playback call sites.
 */
class YoutubeCatalogRepository(
    downloader: OkHttpDownloader = OkHttpDownloader(),
    private val adapter: StreamProviderAdapter = NewPipeStreamProviderAdapter(downloader)
) {
    suspend fun search(
        query: String,
        scope: YoutubeSearchScope = YoutubeSearchScope.EVERYTHING,
        priority: ExtractorWorkPriority = ExtractorWorkPriority.FOREGROUND
    ): List<CatalogTrack> = withContext(ExtractorPriorityContext(priority)) {
        adapter.search(query, scope)
    }

    suspend fun resolveAudio(
        videoUrl: String,
        priority: ExtractorWorkPriority = ExtractorWorkPriority.FOREGROUND
    ): ResolvedAudio = withContext(ExtractorPriorityContext(priority)) {
        adapter.resolveAudio(videoUrl)
    }

    suspend fun resolveAudioExcluding(
        videoUrl: String,
        providers: Set<StreamProvider>,
        priority: ExtractorWorkPriority = ExtractorWorkPriority.FOREGROUND
    ): ResolvedAudio = withContext(ExtractorPriorityContext(priority)) {
        val chainAdapter = adapter as? ResolverChainStreamProviderAdapter
            ?: throw IllegalStateException("Provider failover is unavailable for this catalogue adapter.")
        chainAdapter.resolveAudioExcluding(videoUrl, providers)
    }
}
