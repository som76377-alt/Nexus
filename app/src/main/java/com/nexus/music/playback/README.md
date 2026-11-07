# Playback ownership

`PlaybackService` owns the one ExoPlayer, MediaSession and service-lifetime `ServiceRoomController`.

- `PlaybackConnection` is the Activity-side MediaController bridge.
- `RoomIntentForwardingPlayer` records explicit local MediaSession commands.
- `Media3RoomPlayerPort` applies room state to the same ExoPlayer without emitting another user intent.
- Remote track resolution uses a canonical URL and a fresh local stream URL.

Activities render player/room state and forward user actions. They must not own playback sync, heartbeat, reconnect or drift jobs. Ordinary resume on a prepared item is immediate; new-track preparation uses its bounded start path. OS audio-focus changes remain device-local and must not be misclassified as a user room command.

Android may stop an idle paused service. Do not promise indefinite paused-service lifetime or remote wake after process death.
