> **Status note (2026-11-06):** Room playback synchronization is now coordinated by the concrete `ServiceRoomController` created by `PlaybackService` on the feature branch. `ListeningRoomActivity` observes room state and handles chat presentation; it no longer owns heartbeat, realtime, reconciliation or drift jobs. Physical two-device validation remains outstanding.

# Nexus project map

This document separates implemented source from features still planned or awaiting physical-device validation.

## Repository layout

```text
.
├── .devcontainer/
├── .github/workflows/
│   ├── android-ci.yml
│   └── android-release.yml
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/nexus/music/
│       │   │   ├── MainActivity.kt
│       │   │   ├── CatalogFormatting.kt
│       │   │   ├── catalog/
│       │   │   ├── playback/
│       │   │   ├── rooms/ (domain/, data/, ServiceRoomController.kt)
│       │   │   └── update/
│       │   └── res/values/
│       └── test/java/com/nexus/music/
├── docs/
├── gradle/
├── supabase/migrations/
├── gradlew
├── gradlew.bat
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
```

## Runtime flows

### Music search and playback

1. `MainActivity` accepts a query and selected search scope.
2. `YoutubeCatalogRepository` calls NewPipe off the main thread and converts results into `CatalogTrack`.
3. The user selects a result; the repository resolves a fresh HTTPS audio URL.
4. `AudioStreamSelector` rejects manifest bodies, malformed URLs, and cleartext HTTP; it ranks by bitrate and uses progressive HTTP as a tie-breaker.
5. `PlaybackConnection` converts the resolved data into a Media3 `MediaItem` and sends it to `PlaybackService`.
6. `PlaybackService` owns the one ExoPlayer and MediaSession independently of Activity lifetime.

### Private listening rooms

1. `ListeningRoomActivity` asks the service-owned `RoomsApi` for the device-local anonymous Supabase identity and creates or joins a room with the existing RPCs.
2. The service-owned `ServiceRoomController` attaches to the active room and owns the playback, heartbeat, Realtime, reconciliation, retry, clock and drift jobs.
3. Local MediaSession commands are captured through `RoomIntentForwardingPlayer`; the domain controller publishes them through the membership-checked, revision-CAS RPC.
4. Realtime playback events only wake the controller to fetch an authoritative REST snapshot. Chat events only invalidate the chat view.
5. A remote canonical YouTube URL is resolved separately on each device through NewPipe. The short-lived stream URL remains local and is never shared with the room.
6. The Activity observes controller state and displays room controls/chat. Destroying or navigating away from the Activity does not detach the controller; explicit Leave does.

## Folder responsibilities

- `catalog/`: provider integration, extraction transport, app-owned catalogue models, and stream selection. No Android UI code belongs here.
- `playback/`: service-owned ExoPlayer/MediaSession, Media3 room-player adapter, and explicit MediaSession intent-forwarding boundary.
- `rooms/domain/`: platform/network-independent room models, ports and controller API.
- `rooms/data/`: adapters from the existing authenticated `RoomsApi` and `RoomRealtimeClient` to the room domain.
- `rooms/ServiceRoomController.kt`: concrete service-lifetime room coordinator; owns heartbeat, realtime, revision reconciliation, local command publication, clock calibration and drift correction.
- `rooms/RoomPlaybackMath.kt`: tested, platform-independent clock/drift/retry policy reused by the service controller.
- `rooms/ListeningRoomActivity.kt`: room entry/exit, room controls, chat presentation and state observation. It does not own playback sync jobs.
- `update/`: stable GitHub release checks, APK integrity/signature validation, Android installer hand-off, and legacy background-worker shutdown.
- `supabase/migrations/`: versioned room schema, RLS, RPCs, cleanup scheduling, and least-privilege grants.
- `app/src/test/`: deterministic unit tests that do not require YouTube, plus one opt-in network smoke test.
- `.devcontainer/` and `.github/workflows/`: cloud development and build validation.

## Implemented vs. not implemented

| Area | Current state |
|---|---|
| Android app shell | Implemented |
| YouTube search (All / Songs / Videos) | First page only |
| Thumbnail and result metadata | Implemented |
| Audio stream resolution | Implemented; depends on upstream extractor |
| Media3 playback service/controller | Implemented; physical-device verification remains |
| Background/lock-screen playback | MediaSessionService and media controls implemented; physical-device verification remains |
| In-app update checker and APK verification | Implemented; requires a public stable release feed and permanent signing key |
| Supabase room schema and RLS | Implemented and applied to the Nexus project |
| Room create/join and two-member limit | Implemented; anonymous sign-in must be enabled |
| Room-specific chat | Implemented; UI currently shows latest 100 messages |
| Service-owned shared playback | Implemented in current branch; fresh CI and two-device verification remain |
| Two-phase PREPARE/READY/START barrier | Not implemented |
| One-hour room inactivity expiry | Access blocked at one hour; cron physically deletes within about five additional minutes |
| Durable sign-in and project-wide two-account allowlist | Not implemented |
| End-to-end chat encryption | Not implemented |
| Voice/video calls, attachments, playlists, local library, offline caching | Not implemented |

## Reliability gates before release

- Run CI and review lint/test/build results for the exact commit being released.
- Run the opt-in live extractor smoke test on a supported network.
- Test actual playback, lock-screen/notification controls, audio focus, seeking, route changes, and service recreation on physical Android devices.
- Test room creation/join, isolated chat, idle expiry, track handoff, buffering, simultaneous controls, Activity navigation and reconnect on two physical devices.
- Confirm anonymous sign-in is enabled, then decide how to restrict the project to the two intended accounts.
- Decide whether end-to-end encryption is required for room chat.
- Review platform/service terms, privacy requirements, and NewPipe Extractor GPL-3.0-or-later obligations before distributing binaries.

## Build reproducibility

The repository includes the Gradle Wrapper scripts and JAR, pinned to Gradle 9.6.0. The wrapper verifies the downloaded distribution against Gradle's published SHA-256 checksum. Codespaces bootstrap only installs Android command-line tools; CI and local builds use the same `./gradlew` entrypoint.
