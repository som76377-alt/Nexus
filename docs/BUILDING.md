# Building Nexus

## Required toolchain

- JDK 17 or newer (CI uses JDK 17; the Codespaces image uses JDK 21).
- Android SDK Platform 36.
- Android Build Tools 36.0.0.
- Android Gradle Plugin 9.5.0.

The repository includes the official Gradle Wrapper scripts and JAR. The wrapper downloads the pinned Gradle 9.6.0 distribution and verifies its published SHA-256 checksum. You do not need to install Gradle globally.

## GitHub Codespaces

The repository dev container is based on the Microsoft Java 21 development image. Its post-create bootstrap installs Android command-line tools. The checked-in Gradle Wrapper downloads the exact Gradle version on the first build.

The SDK platform/build-tools packages are installed after the user reviews and accepts the Android SDK licence:

```bash
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

Build the debug APK:

```bash
./gradlew --no-daemon :app:assembleDebug
```

Run unit tests and Android lint:

```bash
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug
```

APK output:

`app/build/outputs/apk/debug/app-debug.apk`

## Live extractor smoke test

The live test intentionally depends on public YouTube availability and is excluded from normal CI. Run it only when you want a real network smoke test:

```bash
./gradlew --no-daemon -Pnexus.liveExtractorTests=true :app:testDebugUnitTest
```

A failure may indicate an upstream extractor/service change or network restrictions; it is not necessarily a regression in the deterministic unit tests.

## GitHub Actions

The `Android CI` workflow installs JDK 17 and Android SDK Platform 36 / Build Tools 36.0.0, then uses the checked-in wrapper to run Android lint, unit tests, and a debug APK build. It uploads the APK as a 14-day artifact. If the `NEXUS_KEYSTORE_*` signing secrets are configured, CI signs debug APKs with the same stable key so later testing builds can update the previous one in place.

## Signed APK and release workflow\n\nSee [UPDATES.md](UPDATES.md) for the required signing-key setup and release process. Do not lose the signing key: Android rejects future in-place updates signed with a different key.\n\n## Physical-device verification

A successful CI build only proves that the app compiles and its configured checks pass. It does not prove that a particular stream resolves or that playback survives on a specific phone.

Install the debug APK and verify:

1. App launches without crashing.
2. Search works for All, Songs, and Videos; changing scope during an active search shows only the newest result set.
3. A known public test stream resolves and starts.
4. Playback continues when another app is opened.
5. Playback continues with the screen locked.
6. Notification and lock-screen controls work.
7. Pause/resume, seek, audio focus, audio becoming noisy, wired/Bluetooth routes, and service/process recreation behave correctly.
8. Status bar, display cutout, gesture navigation, and three-button navigation do not obscure the search UI or mini-player.

The live extraction and physical-device checks must be performed on an actual network/device before calling the app release-ready.
