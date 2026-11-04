# Nexus updates and APK signing

## Update behaviour

Nexus checks the configured GitHub repository's latest published release when the app opens (at most once every six hours), and also provides a manual **Check updates** action. A newer release is downloaded, checked against its SHA-256 digest, validated for package name and version, and handed to Android's package installer.

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

The earlier phone-testing APK was signed with a temporary GitHub runner debug key. Android may therefore require a one-time uninstall/reinstall when moving to the first APK signed with the permanent key. This can remove local app data. After that transition, keep using the same permanent key for every update.

## Publishing a release

Publish a GitHub Release with a semantic version tag such as `v0.1.1`. The **Nexus Signed Release** workflow builds the matching signed APK, calculates its SHA-256 checksum, and attaches:

- `nexus-release.apk`
- `nexus-release.apk.sha256`

Tags must use `vMAJOR.MINOR.PATCH`. The app reads the latest non-draft, non-prerelease GitHub Release and expects the `nexus-release.apk` asset and its SHA-256 digest.

## Android installation and playback notes

The first update may ask you to allow Nexus to install apps from this source in Android settings. Android then asks you to approve each installation; Nexus cannot bypass those system prompts.

During playback, Media3 runs the media foreground service and maintains the media notification with playback controls. A local wake lock is held only while playback is ready or buffering and is released when playback is not active.

Android 13+ also has a notification permission. Declining it does not block playback, although notification visibility can be limited by Android.

`REQUEST_INSTALL_PACKAGES` is restricted by Google Play policy. This implementation is intended for direct testing/sideloaded releases; Google Play distribution requires a separate policy review.

## Current scope

The first tested build includes YouTube search, streaming playback, background/screen-off playback, media notification controls, and update checking. Social rooms, accounts, chat, playlists, pagination, offline media and backend services are not included yet.

NewPipe Extractor is GPL-3.0-or-later; review its license obligations before distributing the app.
