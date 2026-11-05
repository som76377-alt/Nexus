# Nexus

**Nexus is a native Android music-discovery and social-listening prototype.**

It combines YouTube catalogue search, Media3 background playback, an in-app updater, and private two-person listening rooms.

## Current status

### Implemented
- [x] Native Kotlin Android app with a Media3 / ExoPlayer playback service.
- [x] YouTube search scopes: All, Songs, and Videos, using a pinned NewPipe Extractor build.
- [x] Audio stream resolution, HTTPS validation, bitrate-first selection, and short-lived stream URL handling.
- [x] Background playback through MediaSessionService, with system media controls.
- [x] Visible GitHub release checking, download progress, SHA-256 verification, signing-identity validation, and Android installer hand-off.
- [x] Supabase room backend with hashed invite codes, a two-member limit per room, room-scoped messages, per-room playback state, and one-hour idle expiry.
- [x] Android Listening Room entry point with Create Room / Join by Code, chat, shared YouTube-link playback, and play/pause/seek synchronisation through the existing Media3 player.
- [x] Encrypted device-local anonymous room session storage.
- [x] CI lint, unit tests, and debug APK assembly on pull requests.

### Still needs verification or hardening
- [x] Run Android lint, unit tests, and debug APK assembly against the room-reliability branch.
- [ ] Test chat, room expiry, and playback synchronisation on two physical Android devices.
- [ ] Enable Supabase Anonymous Sign-Ins in the Nexus project before using the room prototype.
- [ ] Replace anonymous device-only identity with a durable sign-in and restrict access to the two intended accounts.
- [ ] Add client-side end-to-end encryption if chat confidentiality from the database operator is required.
- [ ] Validate Realtime reconnect, token expiry, and missed-event recovery on two physical devices.
- [ ] Verify stale-seat recovery and explicit Exit Room on two physical devices.
- [ ] Verify YouTube extraction and playback on the target phone/network.
- [ ] Add full chat-history pagination; the current room UI displays the latest 100 messages.
- [ ] Add playlists, queue persistence, and local library support.

The open-source room/sync candidates were inspected and the integration choice is documented in [docs/OPEN_SOURCE_ROOM_SYNC_REVIEW.md](docs/OPEN_SOURCE_ROOM_SYNC_REVIEW.md). Nexus keeps its existing Supabase backend and native Media3 player rather than adding a second room service or an untested audio relay.

## Technology

- Kotlin, Android SDK 36, Gradle Kotlin DSL, and a pinned version catalog
- AndroidX Media3 / ExoPlayer / MediaSession
- NewPipe Extractor and OkHttp for YouTube catalogue/stream extraction
- Kotlin coroutines for off-main-thread network work
- Coil for artwork and thumbnails
- Supabase Postgres, Auth, RLS, and scheduled room cleanup
- Direct Supabase REST/Auth requests over OkHttp; no service-role key is embedded in the APK
- GitHub Actions for lint, tests, and cloud APK builds

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/ROOMS_BACKEND.md](docs/ROOMS_BACKEND.md), [docs/PROJECT_MAP.md](docs/PROJECT_MAP.md), [docs/UPDATES.md](docs/UPDATES.md), and [docs/BUILDING.md](docs/BUILDING.md).

## Build and test

Run deterministic unit tests:

```bash
./gradlew --no-daemon :app:testDebugUnitTest
```

Run the opt-in live extractor smoke test (requires network access):

```bash
./gradlew --no-daemon -Pnexus.liveExtractorTests=true :app:testDebugUnitTest
```

Build a debug APK:

```bash
./gradlew --no-daemon :app:assembleDebug
```

The checked-in Gradle Wrapper pins Gradle 9.6.0 and verifies the downloaded distribution checksum. Codespaces bootstrap installs Android SDK command-line tools; no separate Gradle installation is needed.

## Important limitations

- Supabase Realtime WebSockets carry immediate playback and chat change signals. Authenticated REST reads remain the source-of-truth and recovery path, with slower polling while Realtime is healthy and faster polling while disconnected.
- Playback is synchronized through independent device-local player and source-resolver adapters. It is not sample-accurate and still needs two-phone testing across Wi-Fi/mobile networks.
- A room seat is reclaimed when another device joins after the previous member has missed heartbeats for three minutes; explicit Exit Room removes the current membership immediately once the backend migration is applied.
- Room chat is protected by authenticated membership and RLS, but messages are not end-to-end encrypted.
- Anonymous room identity is stored on one device. Clearing app data or reinstalling can lose that identity and strand its existing room membership.
- The backend currently limits each room to two members; it does not yet enforce a project-wide allowlist for exactly two approved accounts.
- The room UI currently loads the latest 100 messages; older messages remain in the database until room expiry but are not yet paginated in the UI.
- A successful CI build does not prove YouTube extraction or two-device behaviour on every phone/network.

Resolved media URLs may expire. Nexus stores canonical video URLs and resolves a fresh media URL immediately before playback.

NewPipe Extractor is GPL-3.0-or-later. Review its licence obligations before distributing the app.
