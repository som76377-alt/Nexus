# Nexus automatic updates

Nexus now checks for updates when the app is opened:

1. On every app launch, Nexus checks the latest stable GitHub Release and shows one of three clear states in the update panel: checking, up to date, or update available.
2. When an update is available, tap **Download** to begin. Nexus keeps the download in the foreground and displays a progress bar, downloaded size, total size (when provided by GitHub), and percentage.
3. After downloading, Nexus verifies the SHA-256 digest, package name, version code/name, and signing identity before it opens Android's installer.
4. Android still asks you to approve the installation. Nexus cannot silently install an APK on a normal Android device.
5. A failed check or download remains visible in the update panel, with a retry action.

The previous periodic and push-triggered background download jobs are cancelled. A compatibility worker remains in the app only so old scheduled jobs cannot start a silent download.

## Release publishing

Push a stable semantic version tag such as `v0.1.8` to build, verify and publish the signed APK with its SHA-256 digest. A manual workflow dispatch or push to `main` performs signing validation only; only a version-tag push publishes a public release. The app reads only public stable GitHub Releases; no GitHub token is embedded in the APK.

## Important Android limits

- An update check requires an internet connection. When offline, Nexus displays the check error rather than claiming the app is up to date.
- APK downloading begins only after you tap **Download** and stays visible in the app.
- Android installation approval remains required for ordinary sideloaded APK installs.
- The permission to allow installs from Nexus may need to be enabled once in Android settings.
