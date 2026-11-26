package com.nexus.music.catalog

import com.nexus.music.performance.NexusPerformance
import kotlinx.coroutines.currentCoroutineContext

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
        scope: YoutubeSearchScope = YoutubeSearchScope.EVERYTHING
    ): List<CatalogTrack> {
        if (NexusPerformance.isEnabled()) {
            currentCoroutineContext()[NexusPerformance.TraceContext]?.trace?.let {
                NexusPerformance.event(it, "repository_search_entry", "scope=" + scope)
            }
        }
        return adapter.search(query, scope)
    }

    suspend fun resolveAudio(videoUrl: String): ResolvedAudio {
        if (NexusPerformance.isEnabled()) {
            currentCoroutineContext()[NexusPerformance.TraceContext]?.trace?.let {
                NexusPerformance.event(it, "repository_resolve_entry")
            }
        }
        return adapter.resolveAudio(videoUrl)
    }

    suspend fun resolveAudioExcluding(
        videoUrl: String,
        providers: Set<StreamProvider>
    ): ResolvedAudio {
        val chainAdapter = adapter as? ResolverChainStreamProviderAdapter
            ?: throw IllegalStateException("Provider failover is unavailable for this catalogue adapter.")
        return chainAdapter.resolveAudioExcluding(videoUrl, providers)
    }
}
