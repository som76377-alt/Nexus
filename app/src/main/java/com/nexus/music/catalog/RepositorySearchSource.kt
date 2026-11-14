package com.nexus.music.catalog

/**
 * Bridges the existing catalogue facade into SearchSession without changing provider selection.
 *
 * The facade currently exposes one first-page list rather than provider-owned page cursors.
 * Its resolver chain remains responsible for the configured provider fallback.
 */
internal class RepositorySearchSource(
    private val repository: YoutubeCatalogRepository
) : SearchSource {
    // SearchSession requires a stable source identity. The facade owns actual provider selection.
    override val provider: StreamProvider = StreamProvider.NEWPIPE

    override suspend fun search(
        query: String,
        scope: YoutubeSearchScope,
        cursor: SearchCursor?
    ): SearchPage {
        require(cursor == null) { "The catalogue facade does not expose pagination cursors." }
        return SearchPage(
            items = repository.search(query, scope),
            nextCursor = null,
            hasMore = false
        )
    }
}
