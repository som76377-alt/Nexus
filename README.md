# Nexus

**Nexus is a native Android music discovery and social-listening app.**

The intended product combines a broad music/video catalogue, background playback, playlists, and synchronized listening rooms.

## Current status

The first NewPipe integration milestone is now implemented in the codebase:

- [x] Native Kotlin Android application
- [x] Media3 / ExoPlayer service and MediaController integration
- [x] NewPipe Extractor pinned to an upstream-used commit
- [x] OkHttp downloader adapter for NewPipe's Downloader contract
- [x] YouTube search scopes: everything, music songs and videos
- [x] Search result mapping into app-owned catalogue models
- [x] Audio stream resolution, validated HTTPS URLs and bitrate-first quality selection
- [x] Media3 DASH, HLS and SmoothStreaming playback modules
- [x] Native Android search screen with All / Songs / Videos scopes
- [x] Thumbnail result cards with duration and view metadata
- [x] Tap-to-resolve flow with loading, empty and error states
- [x] Coil image loading and compact metadata formatting tests
- [x] MockWebServer tests for downloader HTTP behaviour
- [x] Unit tests for audio stream selection
- [x] Opt-in live YouTube search-and-resolution smoke test
- [ ] Confirm the latest playback integration CI run completes successfully
- [ ] Verify search, thumbnails and stream resolution on a real Android device/network
- [ ] Verify live extraction from a real Android device/network
- [x] Connect resolved audio to the Media3 playback service through MediaController
- [x] Add persistent mini-player with play/pause and seeking
- [x] Configure service audio focus and noisy-route handling
- [ ] Verify background playback and notification controls on a physical Android device
- [ ] Add pagination, playlists and local library
- [ ] Design and implement social listening rooms and chat

## Technology

- Kotlin with Android Gradle Plugin built-in Kotlin support
- AndroidX Media3 / ExoPlayer / MediaSession
- NewPipe Extractor for community-maintained service extraction
- OkHttp transport adapter
- Kotlin coroutines for off-main-thread extraction
- Gradle Kotlin DSL and version catalog
- GitHub Actions for repeatable cloud builds and tests

See [docs/PROJECT_MAP.md](docs/PROJECT_MAP.md) for the codebase map, plus [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md), [docs/BUILDING.md](docs/BUILDING.md), and [docs/NEWPIPE_INTEGRATION.md](docs/NEWPIPE_INTEGRATION.md).

## Build and test

Run the deterministic unit tests:

```bash
./gradlew --no-daemon :app:testDebugUnitTest
```

Run the opt-in live extractor smoke test (requires network access):

```bash
./gradlew --no-daemon -Pnexus.liveExtractorTests=true :app:testDebugUnitTest
```

Build the debug APK:

```bash
./gradlew --no-daemon :app:assembleDebug
```

The repository includes a checked-in Gradle Wrapper that pins Gradle 9.6.0 and verifies the downloaded distribution checksum. The Codespaces bootstrap only installs Android SDK command-line tools; no separate Gradle installation is needed.

## Important

Resolved media URLs may expire. Nexus stores canonical video URLs and resolves a fresh media URL immediately before playback.

NewPipe Extractor is GPL-3.0-or-later. Review licence obligations before distributing a derivative application or binary. The unofficial extractor integration is isolated so upstream service changes can be maintained without rewriting the app's playback or UI layers.
