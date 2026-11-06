> **Implementation-status note (2026-11-06):** This document describes the current implementation. It is not the target architecture. The service-owned RoomController migration is still pending; see [ARCHITECTURE_TARGET.md](ARCHITECTURE_TARGET.md) and [FOUNDATION_STATUS.md](FOUNDATION_STATUS.md).

# Nexus architecture

## Product direction

Nexus is a native Android music-discovery and social-listening prototype. The playback service owns playback state; Activities observe and command it through a Media3 `MediaController`. The UI must never become the source of truth for the active item, queue, playback state, or position.

## Implemented source layout

```text
app/src/main/
  AndroidManifest.xml
  java/com/nexus/music/
    MainActivity.kt
    catalog/
    playback/
    rooms/
      domain/       # player/backend-neutral contracts; concrete controller pending
    update/
```

The current implementation keeps the Android app in one Gradle module. The room UI and room synchronization code are still concentrated in `ListeningRoomActivity`; the new `rooms/domain` contracts establish the boundary for the next refactor but do not implement it.

## Playback

- `PlaybackService` owns the single ExoPlayer and MediaSession.
- `PlaybackConnection` connects Activities to the MediaSession through MediaController.
- The room feature must reuse this player. It must never construct a second ExoPlayer.
- Canonical YouTube URLs are shared; NewPipe resolves a fresh device-local stream URL immediately before playback.

## Room backend and synchronization

The room backend is Supabase Postgres, Auth REST and PostgREST over OkHttp. The client uses anonymous device-local authentication and stores the session encrypted with Android Keystore AES-GCM.

The playback row plus revision-checked `publish_room_playback` RPC is authoritative. Authenticated Realtime Postgres Changes are invalidation signals; clients fetch a fresh snapshot. The current room Activity still owns its heartbeat, polling, reconnect, clock and drift jobs. Moving those jobs to the service-owned RoomController is the next architectural step.

See [ROOMS_BACKEND.md](ROOMS_BACKEND.md), [ROOM_SYNC_RELIABILITY_PLAN.md](ROOM_SYNC_RELIABILITY_PLAN.md), and [ARCHITECTURE_TARGET.md](ARCHITECTURE_TARGET.md).

## Updates

The updater checks GitHub release metadata, verifies package/version, signer identity and SHA-256, then hands the APK to Android's package installer. Release builds require the permanent keystore and explicit semantic version metadata. Debug artifacts are not update-compatible with the official release signer.

WorkManager remains only to cancel old background-update jobs created by earlier versions. The old release-update Firebase messaging service has been removed; remote room wake is not part of v1.

## Important limits

- Service ownership does not guarantee that Android keeps a paused process alive indefinitely.
- Room playback is not sample-accurate and needs physical two-device validation.
- Room chat is not end-to-end encrypted.
- Anonymous room identity is device-local and can be lost if app data is cleared.
