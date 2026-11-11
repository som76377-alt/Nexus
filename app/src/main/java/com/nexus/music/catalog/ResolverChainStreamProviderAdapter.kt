package com.nexus.music.catalog

/**
 * Adapts the shared resolver chain to the stable catalogue facade.
 * The provider field is only an identity required by StreamProviderAdapter; the chain owns
 * actual provider selection and health-aware fallback.
 */
internal class ResolverChainStreamProviderAdapter(
    private val resolver: ProviderResolverChain
) : StreamProviderAdapter {
    override val provider: StreamProvider = StreamProvider.NEWPIPE

    override suspend fun search(
        query: String,
        scope: YoutubeSearchScope
    ): List<CatalogTrack> = resolver.search(query, scope)

    override suspend fun resolveAudio(videoUrl: String): ResolvedAudio =
        resolver.resolveAudio(videoUrl)
}
