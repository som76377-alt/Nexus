# Nexus project map

This document separates what exists in the repository from features that are still ideas or future milestones.

## Repository layout

```text
.
├── .devcontainer/
│   ├── bootstrap.sh              # Verified Gradle + Android CLI tool bootstrap
│   └── devcontainer.json         # Codespaces image and environment
├── .github/workflows/
│   └── android-ci.yml            # Lint, unit tests, debug APK build, artifact upload
├── app/
│   ├── build.gradle.kts          # Android SDK, app config, dependencies, test options
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/nexus/music/
│       │   │   ├── MainActivity.kt
│       │   │   ├── CatalogFormatting.kt
│       │   │   ├── catalog/
│       │   │   └── playback/
│       │   └── res/values/
│       └── test/java/com/nexus/music/
├── docs/
│   ├── ARCHITECTURE.md
│   ├── BUILDING.md
│   ├── DEPENDENCIES.md
│   ├── NEWPIPE_INTEGRATION.md
│   └── PROJECT_MAP.md
├── gradle/
│   └── libs.versions.toml         # Central pinned dependency versions
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
```

## Runtime flow

1. `MainActivity` accepts a query and selected search scope.
2. `YoutubeCatalogRepository` calls NewPipe off the main thread and converts results into `CatalogTrack`.
3. The user selects a result; the repository resolves a fresh HTTPS audio URL.
4. `AudioStreamSelector` rejects manifest bodies, malformed URLs, and cleartext HTTP; it ranks by bitrate and uses progressive HTTP as a tie-breaker.
5. `PlaybackConnection` converts the resolved data into a Media3 `MediaItem` and sends it to the `PlaybackService`.
6. `PlaybackService` owns ExoPlayer and MediaSession independently of the Activity. The mini-player observes the controller state.

## Folder responsibilities

- `catalog/`: provider integration, extraction transport, app-owned catalogue models, and stream selection. No Android UI code belongs here.
- `playback/`: service-owned player/session and the controller bridge. Avoid putting player state in Activity fields.
- `app/src/test/`: deterministic tests that do not require YouTube, plus one opt-in network smoke test.
- `docs/`: architecture, build instructions, dependency/licensing notes, and this map.
- `.devcontainer/` and `.github/workflows/`: reproducible cloud development and build validation.

## Implemented vs. not implemented

| Area | Current state |
|---|---|
| Android app shell | Implemented |
| YouTube search (All / Songs / Videos) | First page only |
| Thumbnail and result metadata | Implemented |
| Audio stream resolution | Implemented; depends on upstream extractor |
| Media3 playback service/controller | Implemented in code; physical-device verification pending |
| Background/lock-screen playback | Intended by service architecture; not yet verified on a physical device |
| Pagination, playlists, local library, persistent queue | Not implemented |
| Accounts, social rooms, chat, WebSocket sync, backend | Not implemented |
| Offline caching and media downloads | Not implemented |
| Release signing and store/distribution pipeline | Not implemented |

## Reliability gates before release

- Run CI and review the lint/test/build results for the exact commit being released.
- Run the opt-in live extractor smoke test on a supported network.
- Test actual playback, lock-screen/notification controls, audio focus, seeking, route changes, and service recreation on physical Android devices.
- Test system-bar insets on gesture navigation, three-button navigation, and display-cutout devices.
- Choose an application licence and review NewPipe Extractor's GPL-3.0-or-later obligations before distributing binaries.
- Review the platform/service terms and privacy requirements for the intended release regions and feature set.

## Known repository tooling limitation

The repository does not yet include Gradle Wrapper scripts or the wrapper JAR. Codespaces bootstrap and CI both install Gradle 9.6.0, but a checked-in wrapper should be added before broad external contributor onboarding.
