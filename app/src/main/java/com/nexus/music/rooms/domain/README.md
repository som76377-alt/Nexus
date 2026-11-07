# Room domain boundary

This package defines the player/backend-independent contract used by the service-owned room engine.

- `RoomModels.kt`: revision-CAS commands, explicit local player intents, authoritative snapshots, connection state and realtime invalidation signals.
- `RoomPorts.kt`: interfaces to the room repository, the one local player and the calibrated clock.
- `RoomController.kt`: the state/session API observed by UI and implemented by the controller owned by PlaybackService.

Rules:

1. This package must not import Android, Media3, OkHttp, Supabase, or Activity classes.
2. The existing ExoPlayer in PlaybackService remains the only audio engine.
3. Local user intent is captured explicitly at the MediaSession/player command boundary; do not infer user actions from timing flags or generic state changes.
4. Applying a remote command must not re-emit a local user intent.
5. The database playback revision and idempotent action UUID remain authoritative in v1.
6. Realtime events are wake-up signals; fetch and compare the database snapshot before applying.
7. Canonical YouTube URLs cross devices. Short-lived NewPipe stream URLs never do.
8. Chat is a separate feature and must never trigger playback reconciliation.
9. No controller jobs are owned by ListeningRoomActivity.
