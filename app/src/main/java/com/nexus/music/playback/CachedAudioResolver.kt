package com.nexus.music.playback

import com.nexus.music.catalog.ResolvedAudio
import com.nexus.music.performance.NexusPerformance

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
    suspend fun resolve(
        sourceUrl: String,
        trace: NexusPerformance.Trace? = null
    ): ResolvedAudio {
        require(sourceUrl.isNotBlank()) { "A canonical source URL is required." }

        cache.fullyCached(sourceUrl)?.let {
            trace?.let { active -> NexusPerformance.event(active, "audio_cache_full_hit") }
            return it
        }
        trace?.let { NexusPerformance.event(it, "audio_cache_full_miss") }

        val now = nowEpochMs()
        cache.freshUrl(sourceUrl, now)?.let {
            trace?.let { active -> NexusPerformance.event(active, "audio_cache_url_hit") }
            return it
        }
        trace?.let { NexusPerformance.event(it, "audio_cache_url_miss") }

        // Provider results are remembered only after the provider chain (including StreamProbe)
        // has accepted them. A thrown resolver result is never cached or played.
        trace?.let { NexusPerformance.event(it, "provider_resolution_begin") }
        val resolved = try {
            resolveFromProviders(sourceUrl)
        } catch (error: Exception) {
            trace?.let {
                NexusPerformance.event(it, "provider_resolution_error",
                    "error=" + error.javaClass.simpleName)
            }
            throw error
        }
        trace?.let {
            NexusPerformance.event(it, "provider_resolution_complete", "provider=" + resolved.provider)
        }
        cache.remember(resolved, nowEpochMs())
        trace?.let { NexusPerformance.event(it, "audio_cache_metadata_saved") }
        return resolved
    }
}
