package com.nexus.music.rooms.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle owner for a single active listening-room session.
 *
 * The production implementation is owned by PlaybackService, not ListeningRoomActivity.
 * UI components observe state and diagnostics, and attach or detach the active room.
 * Playback intents enter through the player port; the controller owns heartbeat,
 * realtime, reconciliation, and drift jobs.
 */
interface RoomController {
    val state: StateFlow<RoomControllerState>
    val diagnostics: StateFlow<RoomDiagnosticsSnapshot>
    suspend fun attach(roomId: String)
    suspend fun detach()
}
