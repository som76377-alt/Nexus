package com.nexus.music.playback

import com.nexus.music.catalog.ResolvedAudio

/**
 * Enforces the offline-first resolution order without coupling it to Media3 or Android storage.
 * A stale URL is never returned for playback, even when a partial byte range is cached.
 */
internal interface AudioCacheLookup {
    suspend fun fullyCached(sourceUrl: String): ResolvedAudio?
    suspend fun freshUrl(sourceUrl: String, nowEpochMs: Long): ResolvedAudio?
    suspend fun remember(audio: ResolvedAudio, nowEpochMs: Long)
    suspend fun evictFailed(sourceUrl: String, audioUrl: String) = Unit
    fun clear() = Unit
}

internal class CachedAudioResolver(
    private val cache: AudioCacheLookup,
    private val resolveFromProviders: suspend (String) -> ResolvedAudio,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    suspend fun resolve(sourceUrl: String): ResolvedAudio {
        require(sourceUrl.isNotBlank()) { "A canonical source URL is required." }

        cache.fullyCached(sourceUrl)?.let { return it }
        val now = nowEpochMs()
        cache.freshUrl(sourceUrl, now)?.let { return it }

        // Provider results are remembered only after the provider chain (including StreamProbe)
        // has accepted them. A thrown resolver result is never cached or played.
        val resolved = resolveFromProviders(sourceUrl)
        cache.remember(resolved, nowEpochMs())
        return resolved
    }
}
