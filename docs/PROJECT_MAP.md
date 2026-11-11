# Nexus project map

For design constraints, see [ARCHITECTURE.md](ARCHITECTURE.md). For current branch and test status, see [FOUNDATION_STATUS.md](FOUNDATION_STATUS.md).

## Android source

- `MainActivity.kt`: catalogue search, result selection, mini-player and update UI.
- `catalog/`: NewPipe initialization, OkHttp downloader, search models, URL validation and audio-stream selection.
- `playback/PlaybackService.kt`: the one ExoPlayer and MediaSession.
- `playback/PlaybackConnection.kt`: Activity-to-service MediaController bridge.
- `playback/Media3RoomPlayerPort.kt`: service-side player port and explicit MediaSession intent capture.
- `rooms/ListeningRoomActivity.kt`: room entry, chat and state rendering.
- `rooms/ServiceRoomController.kt`: service-lifetime room state/reconciliation coordinator.
- `rooms/domain/`: player/backend-neutral commands, snapshots and ports.
- `rooms/data/`: Supabase repository and server-clock adapters.
- `rooms/RoomsApi.kt` and `RoomRealtimeClient.kt`: authenticated REST/RPC and Realtime transport.
- `update/`: release discovery, APK verification and Android install handoff.

## Other areas

- `app/src/test/`: deterministic unit tests; the live extractor test is opt-in.
- `supabase/migrations/`: append-only schema, RLS, RPC, Realtime and cleanup history.
- `web-room-test/`: browser participant for backend room testing; it does not play Android audio.
- `.github/workflows/`: Android CI, signed release and browser harness workflows.
- `docs/`: architecture, backend, dependency, build and acceptance notes.

Do not add an abstraction just to make the map look more layered. Keep a small implementation direct unless a second real implementation or a test seam needs the boundary.
