package com.nexus.music.rooms.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle owner for a single active listening-room session.
 *
 * The production implementation will be created and owned by PlaybackService,
 * not ListeningRoomActivity. UI components observe state and submit intent;
 * they do not own heartbeat, realtime, reconciliation, or drift jobs.
 */
interface RoomController {
    val state: StateFlow<RoomControllerState>
    val diagnostics: StateFlow<RoomDiagnosticsSnapshot>
    suspend fun attach(roomId: String)
    suspend fun detach()
    suspend fun submit(command: RoomCommand): RoomPublishResult
}
