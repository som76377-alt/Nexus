# Nexus foundation status

Last updated: 2026-11-06

## Baseline used for this foundation branch

- Repository: `som76377-alt/Nexus`
- Foundation branch starts from PR #6 head: `ff4c80644ba38551d7c735906b55b84a4843b3ec`.
- PR #6 (room playback reliability and updater baseline) was merged into `main` on 2026-11-06 as merge commit `36bfa816f694426d1a10c35a5e7b0ecbd03ecab9`.
- Main at this foundation review: `36bfa816f694426d1a10c35a5e7b0ecbd03ecab9`.
- Current release at audit: v0.1.7.
- Live Supabase project: active/healthy; migrations through `20261105164854` are recorded as applied. Merging repository code does not apply database migrations.

## Foundation added by this branch

- Player/backend-neutral room domain models.
- Explicit repository, player and clock ports.
- A RoomController lifecycle contract intended to be owned by PlaybackService.
- Documentation index, target package boundaries and architecture decision records.
- Removal of the obsolete release-update-only Firebase messaging service. FCM remote room wake-up is not part of v1.

## Not implemented by this foundation

- No concrete service-owned RoomController implementation yet.
- No migration of existing room jobs out of ListeningRoomActivity yet.
- No new Realtime Broadcast transport.
- No Lamport ordering; database revision CAS remains the v1 ordering authority.
- No two-phase PREPARE/READY/START barrier.
- No proof of two-phone audio synchronization.
- No new APK or release has been built by this foundation branch.

## Required next implementation sequence

1. Confirm this foundation compiles and tests pass.
2. Implement a RoomPlayerPort adapter over the existing MediaSession/ExoPlayer instance.
3. Implement RoomRepository adapters over the existing RoomsApi and RoomRealtimeClient.
4. Implement the concrete RoomController inside PlaybackService.
5. Move heartbeat, playback signals, snapshot reconciliation, clock calibration and drift jobs into that controller.
6. Reduce ListeningRoomActivity to UI, room entry/exit and state observation.
7. Run the two-device acceptance matrix in [ROOM_SYNC_RELIABILITY_PLAN.md](ROOM_SYNC_RELIABILITY_PLAN.md).
8. Only after measured need, evaluate private Broadcast or FCM wake-up.

## Safety rails

- Never delete migrations already applied to the live Supabase project.
- Never reset or re-enable RLS blindly.
- Never embed a service-role key in the Android app or browser client.
- Never replace the permanent release signing key or publish a debug APK as an update.
- Never store or transmit extracted short-lived stream URLs as shared room state.
- Never add a second player for room playback.
