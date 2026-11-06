# Nexus foundation and room-engine status

Last updated: 2026-11-06

## Main baseline

- Repository: `som76377-alt/Nexus`.
- Room playback reliability and updater baseline PR #6 merged as `36bfa816f694426d1a10c35a5e7b0ecbd03ecab9`.
- Architecture foundation PR #7 merged as `fa050c9145488413d04238cf4e9f16cf506f04a7`.
- The current implementation branch is `feature/service-owned-room-controller`, based on that main commit.
- The live Supabase project already has the RPC-only playback write migration `20261105164854` recorded as applied. App code and this branch do not modify the live database.

## Implemented on the service-owned controller branch

- `PlaybackService` creates and owns one concrete `ServiceRoomController` alongside the existing ExoPlayer and MediaSession.
- `Media3RoomPlayerPort` adapts that same ExoPlayer; it does not construct a second player.
- `RoomIntentForwardingPlayer` captures explicit MediaSession play, pause, seek and track-selection intent. Remote room corrections call the player port directly and do not echo through the local-intent wrapper.
- `SupabaseRoomRepository` adapts the existing authenticated `RoomsApi` RPC/REST calls and `RoomRealtimeClient`. Realtime messages are invalidation signals; the controller fetches the authoritative database snapshot.
- `SupabaseRoomClock` reuses the current five-probe clock calibration.
- Heartbeat, Realtime, playback revision reconciliation, conflict recovery, local command publication, clock recalibration and drift correction run in the service controller, not in `ListeningRoomActivity`.
- `ListeningRoomActivity` observes controller state and handles room controls/chat UI. Chat invalidation remains separate from playback reconciliation.
- Focused controller tests cover local track publication, buffered local start, remote track/play without command echo, chat invalidation and detach.

## Explicit limits

- The current branch has not yet passed its fresh Android CI run; do not treat the source as build-verified until the PR checks pass.
- No physical two-phone acceptance run has been completed by CI or this code change.
- No two-phase PREPARE/READY/START barrier is implemented.
- No private Realtime Broadcast transport, Lamport ordering, FCM remote wake, or second player is introduced.
- Android may stop an idle service/process; remote wake after process death is not promised.
- No signed public APK release is produced by this feature branch.

## Validation gates

1. Fresh CI on the exact feature-branch head: Android lint, unit tests, debug APK assembly, unsigned-release fail-closed guard and debug artifact upload.
2. Review any failures and require a new committed head plus a fresh successful CI run.
3. Physical-device acceptance on two Android phones: room create/join, track handoff, play/pause/seek, buffering, simultaneous controls, background playback, Activity navigation/recreation and network reconnect.
4. Release only from a semantic version tag after signed-release workflow validation.

## Safety rails

- Never delete migrations already applied to the live Supabase project.
- Never reset or re-enable RLS blindly.
- Never embed a service-role key in the Android app or browser client.
- Never replace the permanent release signing key or publish a debug APK as an update.
- Never store or transmit extracted short-lived stream URLs as shared room state.
- Never add a second player for room playback.
