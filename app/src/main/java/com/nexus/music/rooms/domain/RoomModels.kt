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

/** User command or OS playback interruption captured at the player boundary. */
sealed interface RoomPlayerIntent {
    data object Play : RoomPlayerIntent
    data object Pause : RoomPlayerIntent
    /** Android paused playback for audio focus loss or becoming-noisy; never publish this as a room command. */
    data object SystemPause : RoomPlayerIntent
    /** Android resumed playback after transient audio focus was regained. */
    data object SystemResume : RoomPlayerIntent
    data class SeekTo(val positionMs: Long) : RoomPlayerIntent
    data class SelectTrack(
        val sourceId: String,
        val canonicalSourceUrl: String,
        val title: String,
        val artist: String?,
        val shouldPlay: Boolean = false
    ) : RoomPlayerIntent
    data class SkipToNext(
        val previousSourceId: String,
        val previousPositionMs: Long,
        val shouldPlay: Boolean = true
    ) : RoomPlayerIntent
    data class SkipToPrevious(
        val previousSourceId: String,
        val previousPositionMs: Long,
        val shouldPlay: Boolean = true
    ) : RoomPlayerIntent
}

/**
 * A user or recovery command against the authoritative room playback revision.
 *
 * expectedRevision is the database compare-and-swap version, not a Lamport counter.
 */
data class RoomCommand(
    val roomId: String,
    val actionId: String,
    val expectedRevision: Long,
    val originDeviceId: String,
    val actionType: RoomActionType,
    /** Database source_id; it may be a video ID rather than the canonical URL. */
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
    /** Database source_id; it may be a video ID rather than the canonical URL. */
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
    val canonicalSourceUrl: String = "",
    val title: String = "",
    val artist: String? = null,
    /** Desired playback state; remains true while audio is buffering. */
    val playWhenReady: Boolean = false,
    /** Actual audio advancement, used only for drift measurement. */
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isReady: Boolean = false,
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

sealed interface RoomRealtimeSignal {
    data object PlaybackInvalidated : RoomRealtimeSignal
    data class PlaybackUpdated(val snapshot: RoomPlaybackSnapshot) : RoomRealtimeSignal
    data object ChatInvalidated : RoomRealtimeSignal
    data class StartScheduled(
        val revision: Long,
        val sourceId: String,
        val startAt: String
    ) : RoomRealtimeSignal
    data class ConnectionChanged(val connected: Boolean) : RoomRealtimeSignal
}

data class RoomPlaybackReadyResult(
    val startAt: String?,
    val activeMembers: Int,
    val readyMembers: Int
)

data class RoomControllerState(
    val roomId: String? = null,
    val connection: RoomConnectionState = RoomConnectionState.DISCONNECTED,
    val playback: RoomPlaybackSnapshot? = null,
    val lastError: String? = null,
    val realtimeConnected: Boolean = false,
    val chatSignalVersion: Long = 0L,
    /** Non-error progress shown while a room operation is running. */
    val statusMessage: String? = null
)

data class RoomPublishResult(
    val applied: Boolean,
    val revision: Long
)

/** Bounded diagnostics for measuring actual transport and playback behavior on devices. */
data class RoomDiagnosticsSnapshot(
    val lastPublishLatencyMs: Long? = null,
    val lastSnapshotLatencyMs: Long? = null,
    val lastApplyLatencyMs: Long? = null,
    val lastDriftMs: Long? = null,
    val lastRoundTripMs: Long? = null,
    val lastCommandAgeMs: Long? = null,
    val events: List<String> = emptyList()
) {
    val summary: String
        get() = "publish=${lastPublishLatencyMs?.let { "${it}ms" } ?: "—"} · " +
            "snapshot=${lastSnapshotLatencyMs?.let { "${it}ms" } ?: "—"} · " +
            "apply=${lastApplyLatencyMs?.let { "${it}ms" } ?: "—"} · " +
            "peer-age=${lastCommandAgeMs?.let { "${it}ms" } ?: "—"} · " +
            "drift=${lastDriftMs?.let { "${it}ms" } ?: "—"} · " +
            "RTT=${lastRoundTripMs?.let { "${it}ms" } ?: "—"}"

    fun exportText(): String = buildString {
        appendLine("Nexus room synchronization diagnostics")
        appendLine(summary)
        appendLine("Events are device-local monotonic observations; compare timestamps across phones only through server-anchored measurements.")
        events.forEach { appendLine(it) }
    }
}
