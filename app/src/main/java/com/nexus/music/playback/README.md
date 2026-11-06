# Playback ownership

`PlaybackService` owns the one ExoPlayer and MediaSession instance.

The room engine must attach to this player through `RoomPlayerPort`; it must not create another player or take ownership of a separate playback timeline.

The next service refactor will create and own one RoomController instance from PlaybackService. Activities connect through MediaController, observe room state and forward user intent. Activity destruction must not cancel the room engine's heartbeat, realtime, reconciliation, clock or drift jobs while the service remains active.

Do not promise indefinite service lifetime. V1 accepts that Android may destroy an idle paused service/process and reconciles on the next app or media-session activation.
