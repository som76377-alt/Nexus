> **Status note (2026-11-06):** The current Activity-owned room jobs are the refactor target. The new `rooms/domain` contracts establish boundaries but do not implement the service-owned controller yet. See [ARCHITECTURE_TARGET.md](ARCHITECTURE_TARGET.md).

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
│       │   │   ├── rooms/
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
6. `PlaybackService` owns ExoPlayer and MediaSession independently of the Activity.

### Private listening rooms

1. `ListeningRoomActivity` asks `RoomsApi` for a device-local anonymous Supabase identity.
2. Create and join actions call authenticated Postgres RPCs; room codes are stored only as SHA-256 hashes.
3. Messages and playback snapshots are always queried by the current room ID.
4. The room Activity sends a heartbeat every 25 seconds and polls chat/playback state.
5. Shared tracks are re-resolved on each device from the canonical YouTube URL and played through the existing Media3 service.
6. The backend blocks room access after one hour of inactivity and a five-minute cron schedule physically deletes expired room rows by cascade.

## Folder responsibilities

- `catalog/`: provider integration, extraction transport, app-owned catalogue models, and stream selection. No Android UI code belongs here.
- `playback/`: service-owned player/session and the controller bridge. Avoid putting player state in Activity fields.
- `rooms/`: Supabase Auth/REST calls, encrypted local session storage, room UI, chat, heartbeat, and playback synchronisation.
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
| Shared playback | Prototype implemented using REST polling; two-device verification remains |
| One-hour room inactivity expiry | Access blocked at one hour; cron physically deletes within about five additional minutes |
| Durable sign-in and project-wide two-account allowlist | Not implemented |
| End-to-end chat encryption | Not implemented |
| Persistent Realtime/WebSocket transport | Not implemented |
| Voice/video calls, attachments, playlists, local library, offline caching | Not implemented |

## Reliability gates before release

- Run CI and review lint/test/build results for the exact commit being released.
- Run the opt-in live extractor smoke test on a supported network.
- Test actual playback, lock-screen/notification controls, audio focus, seeking, route changes, and service recreation on physical Android devices.
- Test room creation/join, isolated chat, reconnection, idle expiry, and synchronised playback on two physical devices.
- Confirm anonymous sign-in is enabled, then decide how to restrict the project to the two intended accounts.
- Decide whether end-to-end encryption is required for room chat.
- Review the platform/service terms, privacy requirements, and NewPipe Extractor GPL-3.0-or-later obligations before distributing binaries.

## Build reproducibility

The repository includes the Gradle Wrapper scripts and JAR, pinned to Gradle 9.6.0. The wrapper verifies the downloaded distribution against Gradle's published SHA-256 checksum. Codespaces bootstrap only installs Android command-line tools; CI and local builds use the same `./gradlew` entrypoint.
