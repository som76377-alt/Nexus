# Nexus

**Nexus is a native Android music discovery and social-listening app.**

The intended product combines a broad music/video catalogue, background playback, playlists, and synchronized listening rooms.

## Current status

This commit establishes the native Android project and its playback foundation. It is **not yet a finished music player**:

- [x] Native Kotlin Android application
- [x] Media3 / ExoPlayer dependencies
- [x] Background playback service and MediaSession skeleton
- [x] NewPipe Extractor dependency pinned to an upstream-used commit
- [x] Codespaces development-container bootstrap
- [x] GitHub Actions debug-build workflow
- [ ] Wire NewPipe Extractor to a maintained HTTP downloader
- [ ] Implement catalogue search and stream resolution
- [ ] Connect the app UI to the playback service
- [ ] Verify background playback on a physical Android device
- [ ] Implement playlists and local library
- [ ] Design and implement social listening rooms and chat

## Technology

- Kotlin with Android Gradle Plugin built-in Kotlin support
- AndroidX Media3 / ExoPlayer / MediaSession
- NewPipe Extractor (unofficial, community-maintained service extraction)
- Gradle Kotlin DSL and version catalog
- GitHub Actions for repeatable cloud builds

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md), and [docs/BUILDING.md](docs/BUILDING.md).

## Build

The repository includes a Codespaces dev container. Rebuild the container after opening the repository to bootstrap JDK, Gradle, and Android command-line tools.

After reviewing and accepting the Android SDK licence in the Codespaces terminal, install the required SDK packages:

```bash
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

Build the debug APK:

```bash
gradle --no-daemon :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

The Android CI workflow also builds the debug APK on pushes and pull requests and uploads it as a workflow artifact.

## Important

Unofficial extractors can stop working when a provider changes its website or internal interfaces. The extraction layer must be isolated, tested, and maintainable. The NewPipe Extractor licence is GPL-3.0-or-later; review the project's distribution/licensing obligations before publishing or distributing a release.

No API keys or backend secrets are required for the initial local playback foundation.
