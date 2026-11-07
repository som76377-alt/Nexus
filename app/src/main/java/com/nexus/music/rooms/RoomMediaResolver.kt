package com.nexus.music.rooms

import com.nexus.music.catalog.ResolvedAudio
import com.nexus.music.catalog.YoutubeCatalogRepository

/**
 * Converts a canonical room track reference into a stream this device can play.
 * Provider-specific resolvers can be replaced without changing room synchronization.
 */
internal interface RoomMediaResolver {
    suspend fun resolve(canonicalSourceUrl: String): ResolvedAudio
}

/** Current provider adapter: every device resolves the canonical YouTube URL locally. */
internal class YoutubeRoomMediaResolver(
    private val catalog: YoutubeCatalogRepository
) : RoomMediaResolver {
    override suspend fun resolve(canonicalSourceUrl: String): ResolvedAudio {
        require(canonicalSourceUrl.isNotBlank()) { "The shared track has no canonical source URL." }
        return catalog.resolveAudio(canonicalSourceUrl)
    }
}
