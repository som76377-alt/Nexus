# Nexus automatic updates

Nexus now has a background update pipeline:

1. WorkManager checks for a stable GitHub release on first app launch and then periodically (every 6 hours, subject to Android battery/background limits).
2. If a newer version exists, Nexus downloads the APK in the background.
3. The updater verifies the SHA-256 digest, package name, version code/name, and signing identity before caching the APK.
4. Nexus posts a notification. Tapping it opens Nexus and shows the Android installation handoff; Android still requires the user to approve installation.
5. Temporary network/API failures are retried by WorkManager with exponential backoff.

## Enable instant release notifications (Firebase Cloud Messaging)

The FCM integration is included but intentionally remains inactive until the Firebase project configuration and sender credentials are supplied.

1. Create a Firebase project and add an Android app with package name `com.nexus.music`.
2. Enable Firebase Cloud Messaging for that project.
3. Download its `google-services.json` and place it at `app/google-services.json` in this repository. This file contains app configuration, not a server credential.
4. Create or select a Google service account with permission to send Firebase Cloud Messaging messages. Store its complete JSON key as the GitHub Actions repository secret `NEXUS_FIREBASE_SERVICE_ACCOUNT`.
5. Store the Firebase project ID as the GitHub Actions repository secret `NEXUS_FIREBASE_PROJECT_ID`.
6. Add the GitHub Actions repository variable `NEXUS_FIREBASE_PUSH_ENABLED` with value `true`.

The app subscribes to the `nexus_updates` topic. When a version tag such as `v0.2.0` is pushed, GitHub Actions builds and verifies the signed APK, publishes the release with its APK/checksum, and then sends an FCM topic message. The app responds by enqueuing the same verified WorkManager update pipeline.

Do not put a service-account JSON key in the repository or inside the APK. Keep it only in GitHub Actions secrets.

## Important Android limits

- Android may defer periodic WorkManager runs to protect battery. The six-hour interval is a requested interval, not an exact alarm.
- FCM gives prompt detection when Google Play services/network delivery are available; it is not a guarantee of instantaneous delivery.
- Android installation approval remains required for ordinary sideloaded APK installs. Nexus cannot silently grant itself install permission.
- Android 13+ notification permission must be allowed for update-ready notifications to appear. If notification permission is denied, the verified update remains cached and Nexus can surface it when opened.
