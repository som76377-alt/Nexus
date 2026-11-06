package com.nexus.music.rooms.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Backend boundary for room playback.
 *
 * Realtime notifications are invalidation/wake-up signals only. Callers must
 * fetch the authoritative snapshot and compare its revision before applying it.
 */
interface RoomRepository {
    suspend fun fetchPlaybackSnapshot(roomId: String): RoomPlaybackSnapshot?
    suspend fun publishPlayback(command: RoomCommand): RoomPublishResult
    fun observePlaybackSignals(roomId: String): Flow<Unit>
    suspend fun touchRoom(roomId: String)
    suspend fun leaveRoom(roomId: String)
}

/**
 * Narrow playback boundary consumed by the room engine.
 *
 * The implementation is backed by the one ExoPlayer owned by PlaybackService.
 * It must never create a second player for room playback.
 */
interface RoomPlayerPort {
    val playback: StateFlow<LocalPlaybackSnapshot>

    /**
     * Explicit local commands captured at the MediaSession/player command boundary.
     * Use a ForwardingPlayer (or equivalent explicit command adapter), not timing
     * windows or heuristics over playWhenReady/position callbacks.
     */
    val userIntents: Flow<RoomPlayerIntent>

    suspend fun prepareCanonicalSource(
        sourceId: String,
        canonicalSourceUrl: String,
        title: String,
        artist: String?,
        playWhenReady: Boolean
    )

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
}

/** Clock boundary so sync math can use elapsed time and deterministic tests. */
interface RoomClock {
    fun elapsedRealtimeMs(): Long
    fun estimatedServerTimeMs(): Long
    fun estimatedRoundTripTimeMs(): Long
    suspend fun calibrate()
}
