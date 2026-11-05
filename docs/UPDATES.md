# Nexus updates and APK signing

## Update behaviour

Nexus checks the latest stable GitHub Release whenever its main screen opens or returns to the foreground. The update panel clearly shows whether it is checking, up to date, or has found a newer version. When an update is available, tap **Download** to start a foreground download with a visible progress bar, downloaded size, and percentage. Nexus verifies the APK's SHA-256 digest, package name, version, and signing identity before opening Android's installer. Failed checks and downloads remain visible and can be retried.

Android displays its own installation approval screen. An ordinary Android app cannot silently install its own updates. When the installed and downloaded APKs use the same package name and signing certificate, Android updates the existing installation and retains app data.

The default public update feed is `som76377-alt/Nexus`. The repository has been made public so the installed app can read its releases without embedding a GitHub token. Never put a personal access token in the APK.

## Permanent signing key

The release workflow expects these repository Actions secrets:

- `NEXUS_KEYSTORE_BASE64`: Base64-encoded permanent JKS keystore
- `NEXUS_KEYSTORE_PASSWORD`: keystore password
- `NEXUS_KEY_ALIAS`: key alias (the documented setup uses `nexus-upload`)
- `NEXUS_KEY_PASSWORD`: private-key password

The keystore must be generated once, kept backed up securely, and never committed to Git. Losing it means future APKs cannot update installations signed with that key.

The release workflow checks that all four secrets are non-empty, decodes the keystore, and uses it to sign the release APK. A successful release workflow run is the definitive validation that the configured secrets and keystore work together. Secret values are never printed by the workflow.

Debug APKs and CI test artifacts are not distribution builds and may use a different signing key. Android will reject an update if the APK signer differs from the installed app. Install the official `nexus-release.apk` from GitHub Releases on every device. If a phone currently has a debug-signed build, the one-time move to the official signed build may require uninstalling the old copy, which can erase its local data; back up anything important first. After that transition, install only official release APKs signed with the permanent key. The first install may request Android's per-device 'install unknown apps' permission.

## Publishing a release

Create and push a semantic version tag such as `v0.1.8` only after the source is ready. The **Nexus Signed Release** workflow builds the version from that tag, verifies the permanent signing certificate, calculates its SHA-256 checksum, and attaches. A push to `main` only runs signed validation and never creates or overwrites a public release. This removes the old hard-coded `v0.1.7` publishing condition.

- `nexus-release.apk`
- `nexus-release.apk.sha256`

Tags must use `vMAJOR.MINOR.PATCH`. Never reuse a published tag for a different build. Keep the same permanent keystore and certificate fingerprint for every update. The app reads the latest non-draft, non-prerelease GitHub Release and expects the `nexus-release.apk` asset and its SHA-256 digest.

## Android installation and playback notes

The first update may ask you to allow Nexus to install apps from this source in Android settings. Android then asks you to approve each installation; Nexus cannot bypass those system prompts.

During playback, Media3 runs the media foreground service and maintains the media notification with playback controls. A local wake lock is held only while playback is ready or buffering and is released when playback is not active.

Android 13+ also has a notification permission. Declining it does not block playback, although notification visibility can be limited by Android.

`REQUEST_INSTALL_PACKAGES` is restricted by Google Play policy. This implementation is intended for direct testing/sideloaded releases; Google Play distribution requires a separate policy review.

## Current scope

The app declares Android 7.0 (API 24) as its minimum OS. This is a compatibility floor, not a promise that every device/ROM, audio codec, network or OEM background policy behaves identically. Validate on multiple Android versions and manufacturers before calling a release device-compatible. The current scope includes YouTube search, streaming playback, background/screen-off playback, media notification controls, room playback and update checking.

NewPipe Extractor is GPL-3.0-or-later; review its license obligations before distributing the app.
