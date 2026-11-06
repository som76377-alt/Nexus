> **Status note (2026-11-06):** The current Activity-owned room jobs are legacy implementation that will move into PlaybackService. The target boundary is documented in [ARCHITECTURE_TARGET.md](ARCHITECTURE_TARGET.md).

# Nexus project map

This document separates implemented source from features still planned or awaiting physical-device validation.

## Repository layout

```text
.
├── .devcontainer/
├── .github/workflows/
├── app/
│   ├── src/main/java/com/nexus/music/
│   │   ├── catalog/       # Search and NewPipe resolution
│   │   ├── playback/      # Media3 service and MediaController bridge
│   │   ├── rooms/          # Current room UI/API plus new domain contracts
│   │   └── update/         # Signed GitHub updater
│   └── src/test/           # Unit tests
├── docs/                   # Architecture, decisions, build and test guides
├── supabase/migrations/    # Versioned schema/RLS/RPC migrations
└── web-room-test/          # Browser backend test participant
```

## Ownership today vs target

| Concern | Current owner | Target owner |
|---|---|---|
| Audio player and MediaSession | PlaybackService | PlaybackService |
| Room heartbeat/reconnect/snapshot/drift jobs | ListeningRoomActivity | RoomController in PlaybackService |
| Room backend REST/RPC | RoomsApi | RoomRepository adapter |
| Realtime invalidation signals | RoomRealtimeClient | RoomRepository adapter |
| Room UI and chat presentation | ListeningRoomActivity | ListeningRoomActivity |
| Clock projection and drift policy | RoomPlaybackMath + Activity loop | RoomController using testable sync policy |

## Important source files

- `MainActivity.kt`: catalogue/search UI and updater entry point.
- `catalog/YoutubeCatalogRepository.kt`: catalogue search.
- `catalog/ExtractorRuntime.kt`: NewPipe initialization.
- `catalog/OkHttpDownloader.kt`: extractor HTTP transport.
- `playback/PlaybackService.kt`: single ExoPlayer and MediaSession owner.
- `playback/PlaybackConnection.kt`: Activity-to-service MediaController bridge.
- `rooms/ListeningRoomActivity.kt`: current room UI and synchronization implementation; this is the main refactor target.
- `rooms/RoomsApi.kt`: Supabase Auth/REST, encrypted session and room data access.
- `rooms/RoomRealtimeClient.kt`: Supabase Realtime WebSocket client.
- `rooms/RoomPlaybackMath.kt`: clock/drift/seek/retry policy.
- `rooms/domain/`: player/backend-neutral contracts added by the foundation branch.
- `update/GitHubReleaseUpdater.kt`: release metadata, verification and download.
- `update/NexusUpdateInstaller.kt`: Android installer hand-off.

## Change discipline

- Keep the current v1 database revision CAS as the only playback command-ordering authority.
- Do not move UI, database and player code in one giant rewrite.
- Move one responsibility at a time and run lint, unit tests and debug assembly after each stage.
- Never delete a live-applied migration as a cleanup action.
- Preserve the permanent release signer and verify the exact tag/build metadata before publishing.
