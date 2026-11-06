# Room domain boundary

This package is the player/backend-independent contract for the listening-room engine.

- `RoomModels.kt`: commands, explicit local player intents, authoritative snapshots, connection state and results.
- `RoomPorts.kt`: interfaces to the room backend, the one local player and the clock.
- `RoomController.kt`: session API observed by UI and owned by PlaybackService.

Rules:

1. This package must not import Android, Media3, OkHttp, Supabase, or Activity classes.
2. The existing ExoPlayer in PlaybackService remains the only audio engine.
3. Local user intent must be captured explicitly at the MediaSession/player command boundary. Do not infer user actions from timing flags or generic state changes.
4. Applying a remote command must not re-emit a local user intent.
5. The database playback revision and idempotent action UUID remain authoritative in v1.
6. Realtime events are wake-up signals; fetch and compare the database snapshot before applying.
7. Canonical YouTube URLs cross devices. Short-lived NewPipe stream URLs never do.
8. Chat is a separate feature and must never trigger playback reconciliation.
9. No controller jobs are owned by ListeningRoomActivity.
