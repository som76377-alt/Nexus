# Playback ownership

`PlaybackService` owns the one ExoPlayer, MediaSession and service-lifetime `ServiceRoomController`.

The room engine talks to the existing player through `Media3RoomPlayerPort`; it must not create another player or own a separate playback timeline. A MediaSession-facing `RoomIntentForwardingPlayer` records explicit local controller commands. Remote synchronization calls the player port directly and must not echo those changes back as local intent.

Activities connect through MediaController, observe room state and forward user intent. Full audio-focus loss, transient focus suppression, and headphone-unplug pauses are forwarded as room PAUSE intents; focus regain does not independently resume a room. The user resumes through the shared PLAY barrier. Activity destruction must not cancel the room engine's heartbeat, realtime, reconciliation, clock or drift jobs while the service remains active. Explicit Leave detaches the active room session.

Do not promise indefinite service lifetime. V1 accepts that Android may destroy an idle paused service/process and reconciles on the next app or media-session activation.
