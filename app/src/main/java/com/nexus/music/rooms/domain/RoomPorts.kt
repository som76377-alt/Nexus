package com.nexus.music.rooms.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Backend boundary for room playback.
 *
 * Realtime notifications are invalidation/wake-up signals only. Callers fetch the
 * authoritative snapshot and compare its revision before applying it.
 */
interface RoomRepository {
    suspend fun currentUserId(): String
    suspend fun fetchPlaybackSnapshot(roomId: String): RoomPlaybackSnapshot?
    suspend fun publishPlayback(command: RoomCommand): RoomPublishResult
    suspend fun markPlaybackReady(
        roomId: String,
        revision: Long,
        sourceId: String
    ): RoomPlaybackReadyResult
    fun observeRealtimeSignals(roomId: String): Flow<RoomRealtimeSignal>
    suspend fun touchRoom(roomId: String): Boolean
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
    val userIntents: Flow<RoomPlayerIntent>

    /** Read a fresh position/state; StateFlow is for observation, not a clock. */
    fun snapshot(): LocalPlaybackSnapshot

    /** Resolve externally, then replace the item in the one local player. */
    suspend fun prepareCanonicalSource(
        sourceId: String,
        canonicalSourceUrl: String,
        streamUrl: String,
        title: String,
        artist: String?,
        mimeType: String?
    )

    /** These methods apply remote state directly and must not emit local user intent. */
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun resetPlaybackSpeed()
}

/** Clock boundary so sync math can use elapsed time and deterministic tests. */
interface RoomClock {
    fun elapsedRealtimeMs(): Long
    fun estimatedServerTimeMs(): Long
    fun estimatedRoundTripTimeMs(): Long
    /** False means callers must not use this clock to schedule synchronized playback. */
    fun isCalibrated(): Boolean = true
    suspend fun calibrate()
}
