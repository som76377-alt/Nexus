# Nexus implementation status

_Last audited: 9 November 2026._

## Source-of-truth branches

- `main` is the current release baseline at `53dfeb19f44156086c953a523588f37ddf125588`.
- That exact commit passed [Android CI](https://github.com/som76377-alt/Nexus/actions/runs/42560364235), [the web-room-test build](https://github.com/som76377-alt/Nexus/actions/runs/42560364108), and [signed-release validation](https://github.com/som76377-alt/Nexus/actions/runs/42560364082).
- At the start of this audit, `integration/room-v2` was at `34f3c03b596d28b20f689a208a33465ad4441427`, 15 commits behind `main`. The cleanup in PR #35 is scoped to integration and does not change `main`.
- PR #10 was merged on 8 November 2026.
- PR #9 (`fix/room-expired-stream-recovery`) has no unique commits ahead of either `main` or `integration/room-v2`; its head is already contained in both. It should not be merged again.
- PR #11 (`refactor/nexus-simplification`) is not a safe blind merge: its head is 104 commits ahead and 31 behind `main`, with merge base `550774758aa018d2f391ff30c0398aeb150a32be`. Review and selectively rebase only changes still needed.

## Current implementation on main

- One service-owned ExoPlayer and MediaSession, with a service-owned room controller.
- NewPipe-based YouTube catalogue search and per-device resolution of short-lived audio streams.
- Supabase-backed two-member rooms, room-scoped chat, revision-checked playback commands, private Realtime delivery and REST recovery.
- Bounded new-track readiness/start handling, local fallback, drift correction and room diagnostics.
- GitHub-release updater with download digest, package/version and signing-identity validation.
- Android CI for lint, JVM unit tests, debug APK assembly and release-publishing guard; a separate browser room-test harness build.

## Maintainability findings

- The source tree contains no byte-for-byte duplicate files. The 18 Supabase migrations have distinct contents and remain immutable history.
- The browser room client is a useful command/diagnostic participant, but its CI job only builds the Vite app; it is not a live Supabase + Android end-to-end test.
- WorkManager compatibility code remains to cancel jobs scheduled by older app versions. The Gradle wrapper, Coil/OkHttp, NewPipe, Media3, coroutines, and DASH/HLS support are used by the app.
- Confirmed cleanup in PR #35: remove an empty companion object, one unused coroutine import, and the direct `core-ktx` dependency with no repository source references. No complete source/test/migration/document file was proven safe to delete.

## Known limits and unverified behaviour

- No timestamped two-physical-device acceptance evidence is recorded. Actual sync latency/drift, screen-locked operation, interruptions and reconnect behaviour remain UNVERIFIED.
- The 26 controller unit tests cover S1/S2/S3/S5/S7/S8; I found no explicit S6 end-of-track regression test. S4 screen-lock behaviour requires physical-device testing.
- Room identity is anonymous and device-local, not a durable account allowlist. Clearing app data can lose it.
- Chat is protected by room membership/RLS but is not end-to-end encrypted; the UI currently loads only the latest 100 messages.
- YouTube extraction depends on an unofficial upstream extractor. Review its GPL-3.0-or-later obligations and choose an application licence before public distribution.

## Verification gates

1. Run Android lint, the complete unit-test suite, debug APK assembly and the release-publishing guard on the exact candidate commit.
2. Run the web-room-test build when room transport/client code changes.
3. Install and test the candidate APK for search, local/background playback, MediaSession controls, updater and room UI.
4. Complete the two-phone checklist in [ROOM_SYNC_RELIABILITY_PLAN.md](ROOM_SYNC_RELIABILITY_PLAN.md), recording diagnostics from both devices.
5. Do not claim device-level sync or release readiness from CI alone.
