package com.nexus.music.catalog

import com.nexus.music.performance.NexusPerformance
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Search and stream-resolution facade shared by the app and playback service. */
class YoutubeCatalogRepository(
    downloader: OkHttpDownloader = ExtractorRuntime.normalDownloader,
    private val adapter: StreamProviderAdapter = NewPipeStreamProviderAdapter(downloader)
) {
    private val searchCoordinator = SearchRequestCoordinator<CatalogTrack>(
        promoteRequest = ExtractorRuntime::promote
    )

    suspend fun search(
        query: String,
        scope: YoutubeSearchScope = YoutubeSearchScope.EVERYTHING
    ): List<CatalogTrack> = searchWithPriority(query, scope, ExtractorWorkPriority.FOREGROUND)

    /** Home discovery opts into BACKGROUND; shared requests retain and can raise this priority. */
    internal suspend fun searchWithPriority(
        query: String,
        scope: YoutubeSearchScope,
        priority: ExtractorWorkPriority
    ): List<CatalogTrack> {
        val ownerJob: Job? = currentCoroutineContext()[Job]
        val trace = currentCoroutineContext()[NexusPerformance.TraceContext]?.trace
        if (NexusPerformance.isEnabled() && trace != null) {
            NexusPerformance.event(trace, "repository_search_entry", "scope=" + scope)
        }
        return withContext(ExtractorPriorityContext(priority, ownerJob)) {
            searchCoordinator.search(query, scope, priority) { normalizedQuery ->
                adapter.search(normalizedQuery, scope)
            }
        }
    }

    /** Promote the exact speculative extraction when the user taps its matching track. */
    internal fun promoteAudioResolution(ownerJob: Job): Boolean = ExtractorRuntime.promote(ownerJob)

    suspend fun resolveAudio(videoUrl: String): ResolvedAudio =
        resolveAudioWithPriority(videoUrl, ExtractorWorkPriority.FOREGROUND)

    internal suspend fun resolveAudioWithPriority(
        videoUrl: String,
        priority: ExtractorWorkPriority
    ): ResolvedAudio {
        val ownerJob: Job? = currentCoroutineContext()[Job]
        currentCoroutineContext()[NexusPerformance.TraceContext]?.trace?.let {
            if (NexusPerformance.isEnabled()) NexusPerformance.event(it, "repository_resolve_entry")
        }
        return withContext(ExtractorPriorityContext(priority, ownerJob)) {
            adapter.resolveAudio(videoUrl)
        }
    }

    suspend fun resolveAudioExcluding(
        videoUrl: String,
        providers: Set<StreamProvider>
    ): ResolvedAudio = resolveAudioExcludingWithPriority(
        videoUrl, providers, ExtractorWorkPriority.FOREGROUND
    )

    internal suspend fun resolveAudioExcludingWithPriority(
        videoUrl: String,
        providers: Set<StreamProvider>,
        priority: ExtractorWorkPriority
    ): ResolvedAudio {
        val ownerJob: Job? = currentCoroutineContext()[Job]
        return withContext(ExtractorPriorityContext(priority, ownerJob)) {
            val chainAdapter = adapter as? ResolverChainStreamProviderAdapter
                ?: throw IllegalStateException(
                    "Provider failover is unavailable for this catalogue adapter."
                )
            chainAdapter.resolveAudioExcluding(videoUrl, providers)
        }
    }
}
