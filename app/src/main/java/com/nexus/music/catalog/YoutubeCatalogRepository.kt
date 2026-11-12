package com.nexus.music.catalog

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
    ): List<CatalogTrack> = adapter.search(query, scope)

    suspend fun resolveAudio(videoUrl: String): ResolvedAudio = adapter.resolveAudio(videoUrl)
}
