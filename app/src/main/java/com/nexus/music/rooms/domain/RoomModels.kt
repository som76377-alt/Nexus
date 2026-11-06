package com.nexus.music.rooms.domain

/**
 * Stable domain vocabulary for the listening-room engine.
 *
 * These types intentionally do not depend on Android, Media3, OkHttp, or Supabase.
 * Wire/database conversion belongs in the data layer.
 */
enum class RoomActionType(val wireValue: String) {
    TRACK("TRACK"),
    PLAY("PLAY"),
    PAUSE("PAUSE"),
    SEEK("SEEK"),
    RECOVERY("RECOVERY"),
    STATE("STATE");

    companion object {
        fun fromWireValue(value: String): RoomActionType? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Explicit local user intent captured at the player command boundary.
 *
 * The player adapter must emit these only for genuine local user commands.
 * Applying a remote room command must never emit another local intent.
 */
sealed interface RoomPlayerIntent {
    data object Play : RoomPlayerIntent
    data object Pause : RoomPlayerIntent
    data class SeekTo(val positionMs: Long) : RoomPlayerIntent
    data class SelectTrack(
        val sourceId: String,
        val canonicalSourceUrl: String,
        val title: String,
        val artist: String?,
        val durationMs: Long
    ) : RoomPlayerIntent
}

/**
 * A user or recovery command against the authoritative room playback revision.
 *
 * expectedRevision is the database compare-and-swap version. It is deliberately
 * not a Lamport counter: revision CAS remains the ordering authority in v1.
 */
data class RoomCommand(
    val roomId: String,
    val actionId: String,
    val expectedRevision: Long,
    val originDeviceId: String,
    val actionType: RoomActionType,
    val sourceId: String,
    val canonicalSourceUrl: String,
    val title: String,
    val artist: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long
) {
    init {
        require(roomId.isNotBlank()) { "roomId must not be blank" }
        require(actionId.isNotBlank()) { "actionId must not be blank" }
        require(expectedRevision >= 0L) { "expectedRevision must be non-negative" }
        require(originDeviceId.isNotBlank()) { "originDeviceId must not be blank" }
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(canonicalSourceUrl.isNotBlank()) { "canonicalSourceUrl must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(positionMs >= 0L) { "positionMs must be non-negative" }
        require(durationMs >= 0L) { "durationMs must be non-negative" }
    }
}

/** Authoritative playback row read from the room backend. */
data class RoomPlaybackSnapshot(
    val roomId: String,
    val sourceId: String,
    val canonicalSourceUrl: String,
    val title: String,
    val artist: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val revision: Long,
    val updatedBy: String,
    val updatedAt: String,
    val actionId: String,
    val actionType: RoomActionType,
    val originDeviceId: String?,
    val commandAt: String
) {
    init {
        require(revision >= 0L) { "revision must be non-negative" }
        require(positionMs >= 0L) { "positionMs must be non-negative" }
        require(durationMs >= 0L) { "durationMs must be non-negative" }
    }
}

/** Small player-neutral snapshot; Media3 constants never cross this boundary. */
data class LocalPlaybackSnapshot(
    val sourceId: String = "",
    val title: String = "",
    val artist: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

enum class RoomConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONCILING,
    ERROR
}

data class RoomControllerState(
    val roomId: String? = null,
    val connection: RoomConnectionState = RoomConnectionState.DISCONNECTED,
    val playback: RoomPlaybackSnapshot? = null,
    val lastError: String? = null
)

data class RoomPublishResult(
    val applied: Boolean,
    val revision: Long
)
