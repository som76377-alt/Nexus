package com.nexus.music.library

import com.nexus.music.catalog.CatalogTrack
import com.nexus.music.playback.PlaybackSnapshot

/**
 * Counts observed foreground UI time only while the player reports active playback.
 * A shared instance prevents overlapping Activities from recording the same session twice.
 */
class PlaybackHistoryTracker(
    private val requiredPlaybackMillis: Long = 10_000L
) {
    private var activeVideoId: String? = null
    private var accumulatedMillis = 0L
    private var lastObservedAtMillis: Long? = null
    private var recorded = false

    @Synchronized
    fun observe(
        snapshot: PlaybackSnapshot,
        track: CatalogTrack?,
        elapsedRealtimeMillis: Long,
        onThresholdReached: (CatalogTrack) -> Unit
    ): Boolean {
        val trackId = track?.let { LibraryTrack.from(it)?.id }
        val sourceId = YoutubeVideoId.fromUrl(snapshot.sourceId)
        if (!snapshot.connected || trackId == null || sourceId != trackId) {
            reset()
            return false
        }

        if (activeVideoId != trackId) {
            activeVideoId = trackId
            accumulatedMillis = 0L
            lastObservedAtMillis = null
            recorded = false
        }

        if (!snapshot.isPlaying) {
            lastObservedAtMillis = null
            return false
        }

        lastObservedAtMillis?.let { previous ->
            val elapsed = (elapsedRealtimeMillis - previous).coerceAtLeast(0L)
            // A delayed UI callback must not count time when the screen was not observing playback.
            accumulatedMillis += elapsed.coerceAtMost(1_000L)
        }
        lastObservedAtMillis = elapsedRealtimeMillis

        if (accumulatedMillis < requiredPlaybackMillis || recorded) return false
        recorded = true
        onThresholdReached(track)
        return true
    }

    @Synchronized
    fun suspendObservation() {
        lastObservedAtMillis = null
    }

    @Synchronized
    fun reset() {
        activeVideoId = null
        accumulatedMillis = 0L
        lastObservedAtMillis = null
        recorded = false
    }

    companion object {
        val shared = PlaybackHistoryTracker()
    }
}
