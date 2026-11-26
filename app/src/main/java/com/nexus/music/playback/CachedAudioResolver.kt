package com.nexus.music.playback

import com.nexus.music.catalog.ResolvedAudio
import com.nexus.music.performance.NexusPerformance
import kotlinx.coroutines.CancellationException

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
        val tracing = trace != null && NexusPerformance.isEnabled()

        var stageStartedAt = if (tracing) NexusPerformance.nowMs() else 0L
        if (tracing) NexusPerformance.event(trace!!, "audio_cache_full_lookup_begin")
        val fullyCached = cache.fullyCached(sourceUrl)
        if (tracing) {
            NexusPerformance.event(trace!!, "audio_cache_full_lookup_complete",
                "duration_ms=" + (NexusPerformance.nowMs() - stageStartedAt) +
                    " result=" + if (fullyCached == null) "miss" else "hit")
        }
        fullyCached?.let { return it }

        val now = nowEpochMs()
        stageStartedAt = if (tracing) NexusPerformance.nowMs() else 0L
        if (tracing) NexusPerformance.event(trace!!, "audio_cache_fresh_url_lookup_begin")
        val freshUrl = cache.freshUrl(sourceUrl, now)
        if (tracing) {
            NexusPerformance.event(trace!!, "audio_cache_fresh_url_lookup_complete",
                "duration_ms=" + (NexusPerformance.nowMs() - stageStartedAt) +
                    " result=" + if (freshUrl == null) "miss" else "hit")
        }
        freshUrl?.let { return it }

        // Provider results are remembered only after the provider chain (including StreamProbe)
        // has accepted them. A thrown resolver result is never cached or played.
        stageStartedAt = if (tracing) NexusPerformance.nowMs() else 0L
        if (tracing) NexusPerformance.event(trace!!, "provider_resolution_begin")
        val resolved = try {
            if (tracing) {
                kotlinx.coroutines.withContext(NexusPerformance.TraceContext(trace!!)) {
                    resolveFromProviders(sourceUrl)
                }
            } else {
                resolveFromProviders(sourceUrl)
            }
        } catch (cancelled: CancellationException) {
            if (tracing) NexusPerformance.event(trace!!, "provider_resolution_cancelled")
            throw cancelled
        } catch (error: Exception) {
            if (tracing) {
                NexusPerformance.event(trace!!, "provider_resolution_error",
                    "duration_ms=" + (NexusPerformance.nowMs() - stageStartedAt) +
                        " error=" + error.javaClass.simpleName)
            }
            throw error
        }
        if (tracing) {
            NexusPerformance.event(trace!!, "provider_resolution_complete",
                "duration_ms=" + (NexusPerformance.nowMs() - stageStartedAt) +
                    " provider=" + resolved.provider)
        }

        stageStartedAt = if (tracing) NexusPerformance.nowMs() else 0L
        if (tracing) NexusPerformance.event(trace!!, "audio_cache_metadata_save_begin")
        cache.remember(resolved, nowEpochMs())
        if (tracing) {
            NexusPerformance.event(trace!!, "audio_cache_metadata_save_complete",
                "duration_ms=" + (NexusPerformance.nowMs() - stageStartedAt))
        }
        return resolved
    }
}
