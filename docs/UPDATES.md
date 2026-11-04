# Nexus updates and APK signing

## What the app does

Nexus checks the configured GitHub repository's latest published release when the app opens (at most once every six hours), and also has a manual **Check updates** button. A newer release appears in the app. Nexus downloads the APK, verifies its SHA-256 digest, checks that its package name and version match the release, and hands it to Android's package installer.

Android still displays its own installation approval screen. Ordinary Android apps cannot silently install updates themselves. When the installed and downloaded APKs use the same package name and signing certificate, Android updates the existing installation and retains its app data.

## Important: the current repository is private

The current source repository, `som76377-alt/Nexus`, is private. An installed APK cannot read private GitHub Releases anonymously, and an access token must never be embedded in the APK. Before in-app update checks can work on the phone, configure a publicly readable release repository.

You can either make the source repository public, if you are comfortable publishing its source, or create a separate public repository for release assets and point the app's update-feed properties at it. A separate public release repository also needs the release workflow configured to publish assets there.

The app currently defaults to `som76377-alt/Nexus` and will show a clear error while that feed remains private. The feed can be changed at build time with `-PnexusUpdateOwner=...` and `-PnexusUpdateRepository=...`.

## One-time signing-key setup

Android only accepts an in-place update when the new APK has the same signing certificate as the installed APK. Create one permanent key and keep a secure backup. Never commit the keystore to Git.

In the Codespaces terminal, create the key:

```bash
keytool -genkeypair -v -keystore nexus-upload.jks -storetype JKS -keyalg RSA -keysize 3072 -validity 10000 -alias nexus-upload
```

Convert it to one Base64 line:

```bash
base64 -w 0 nexus-upload.jks
```

In GitHub, open **Settings → Secrets and variables → Actions** and add these repository secrets:

- `NEXUS_KEYSTORE_BASE64`: the Base64 output from the command above
- `NEXUS_KEYSTORE_PASSWORD`: the keystore password
- `NEXUS_KEY_ALIAS`: `nexus-upload`
- `NEXUS_KEY_PASSWORD`: the key password

Keep the original `nexus-upload.jks` and passwords somewhere safe. If you lose the key, Android will not allow future APKs signed with a replacement key to update the existing installation.

When these secrets exist, subsequent CI debug APKs and release APKs use the same key. The earlier CI APK built before this key was configured uses a temporary runner debug key; replacing that first installation may require a one-time uninstall/reinstall.

## Publishing a release

After the signing secrets exist, create a GitHub Release with a semantic version tag such as `v0.1.1`. The `Nexus Signed Release` workflow builds a signed APK, creates a checksum file, and attaches:

- `nexus-release.apk`
- `nexus-release.apk.sha256`

The updater reads the latest non-draft, non-prerelease GitHub Release and requires the `nexus-release.apk` asset with GitHub's SHA-256 digest. Release tags must be `vMAJOR.MINOR.PATCH`.

The app must be able to read that repository's releases without a GitHub login. Do not add a personal access token to the app.

## Android permission and battery behaviour

The first update installation may ask you to allow Nexus to install apps from this source in Android settings. Android then asks you to approve each installation. These are system safety controls; Nexus cannot bypass them.

During actual playback, Media3 runs the media foreground service, maintains the media notification with playback controls, and holds a local wake lock while playback is ready or buffering. It releases the wake lock when playback is not active. This protects music playback without keeping the phone awake forever or running an unrelated permanent notification.

Android 13+ also has a notification permission. Nexus asks the first time you select a track; declining it does not block playback, although notification visibility can be limited by Android.

`REQUEST_INSTALL_PACKAGES` is restricted by Google Play policy. This implementation is intended for direct testing/sideloaded releases; distribution through Google Play would require a separate policy review.
