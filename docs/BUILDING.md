# Building Nexus

## GitHub Codespaces

The repository dev container is based on the Microsoft Java 21 development image. Its post-create bootstrap installs:

- Gradle 9.6.0
- Android command-line tools
- Android SDK manager

The SDK platform/build-tools packages are installed after the user reviews and accepts the Android SDK licence.

Open the Codespaces terminal and run:

```bash
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

Then build:

```bash
gradle --no-daemon :app:assembleDebug
```

APK output:

`app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions

The `Android CI` workflow installs JDK 17, Android SDK packages, and Gradle 9.6.0, then builds the debug APK. Download the artifact from the successful workflow run.

## Physical-device verification

A successful CI build only proves that the app compiles. It does not prove that a particular stream resolves or that playback survives on a specific phone.

For device testing, install the debug APK, then verify:

1. App launches without crashing.
2. Playback service can be connected to.
3. A known test stream starts and plays.
4. Audio continues when another app is opened.
5. Audio continues with the screen locked.
6. Notification and lock-screen controls work.
7. Pause/resume, seek, queue changes, audio focus, and Bluetooth controls behave correctly.

The catalogue-to-player integration and a physical test run are still pending.
