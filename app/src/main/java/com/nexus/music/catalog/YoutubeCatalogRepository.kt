package com.nexus.music.catalog

/**
 * Search and stream-resolution facade used by the app and playback service.
 *
 * The default adapter is the existing NewPipe implementation. Keeping this facade stable lets
 * the resolver chain be introduced without changing current UI or playback call sites.
 */
class YoutubeCatalogRepository(
    downloader: OkHttpDownloader = OkHttpDownloader(),
    private val adapter: StreamProviderAdapter = NewPipeStreamProviderAdapter(downloader),
    private val streamProbe: StreamProbe? = null
) {
    suspend fun search(
        query: String,
        scope: YoutubeSearchScope = YoutubeSearchScope.EVERYTHING
    ): List<CatalogTrack> = adapter.search(query, scope)

    suspend fun resolveAudio(videoUrl: String): ResolvedAudio {
        val audio = adapter.resolveAudio(videoUrl)
        return streamProbe?.probe(audio) ?: audio
    }

    suspend fun resolveAudioNext(videoUrl: String, afterProvider: StreamProvider?): ResolvedAudio {
        val failover = adapter as? StreamProviderFailoverAdapter
            ?: throw java.io.IOException("This catalogue does not support provider continuation.")
        val audio = failover.resolveAudioNext(videoUrl, afterProvider)
        return streamProbe?.probe(audio) ?: audio
    }
}
