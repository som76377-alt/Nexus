# Nexus

**Nexus is a native Android music discovery and social-listening app.**

The intended product combines a broad music/video catalogue, background playback, playlists, and synchronized listening rooms.

## Current status

The first NewPipe integration milestone is now implemented in the codebase:

- [x] Native Kotlin Android application
- [x] Media3 / ExoPlayer dependencies and background service skeleton
- [x] NewPipe Extractor pinned to an upstream-used commit
- [x] OkHttp downloader adapter for NewPipe's Downloader contract
- [x] YouTube search scopes: everything, music songs and videos
- [x] Search result mapping into app-owned catalogue models
- [x] Audio stream resolution and direct-URL quality selection
- [x] Native Android search screen with All / Songs / Videos scopes
- [x] Thumbnail result cards with duration and view metadata
- [x] Tap-to-resolve flow with loading, empty and error states
- [x] Coil image loading and compact metadata formatting tests
- [x] MockWebServer tests for downloader HTTP behaviour
- [x] Unit tests for audio stream selection
- [x] Opt-in live YouTube search-and-resolution smoke test
- [ ] Confirm the latest search-screen CI run completes successfully
- [ ] Verify search, thumbnails and stream resolution on a real Android device/network
- [ ] Verify live extraction from a real Android device/network
- [ ] Connect resolved audio to the Media3 playback service
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

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md), [docs/BUILDING.md](docs/BUILDING.md), and [docs/NEWPIPE_INTEGRATION.md](docs/NEWPIPE_INTEGRATION.md).

## Build and test

Run the deterministic unit tests:

```bash
gradle --no-daemon :app:testDebugUnitTest
```

Run the opt-in live extractor smoke test (requires network access):

```bash
gradle --no-daemon -Pnexus.liveExtractorTests=true :app:testDebugUnitTest
```

Build the debug APK:

```bash
gradle --no-daemon :app:assembleDebug
```

The repository includes a Codespaces dev container and a GitHub Actions workflow that builds the debug APK and runs unit tests.

## Important

Resolved media URLs may expire. Nexus stores canonical video URLs and resolves a fresh media URL immediately before playback.

NewPipe Extractor is GPL-3.0-or-later. Review licence obligations before distributing a derivative application or binary. The unofficial extractor integration is isolated so upstream service changes can be maintained without rewriting the app's playback or UI layers.
