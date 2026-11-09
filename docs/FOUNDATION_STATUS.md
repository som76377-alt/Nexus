# Nexus implementation status

_Last audited: 9 November 2026._

## Source-of-truth branches

- `main` is the current release baseline at `53dfeb19f44156086c953a523588f37ddf125588`.
- The exact `main` commit passed Android CI, the web-room-test workflow, and signed-release validation: [Android CI](https://github.com/som76377-alt/Nexus/actions/runs/42560364235), [web room tests](https://github.com/som76377-alt/Nexus/actions/runs/42560364108), [signed-release validation](https://github.com/som76377-alt/Nexus/actions/runs/42560364082).
- `integration/room-v2` is at `34f3c03b596d28b20f689a208a33465ad4441427`, 15 commits behind `main`. Changes made there are not automatically present in the current release baseline.
- PR #10 was merged on 8 November 2026. It is no longer a separate unmerged reliability branch.
- PR #11 (simplification) and PR #9 (expired-stream recovery) remain open drafts based on older feature/reliability branches. Neither should be assumed to be part of current `main`; review and rebase selectively before using any of their changes.

## Current implementation on main

- One service-owned ExoPlayer and MediaSession, with a service-owned room controller.
- NewPipe-based YouTube catalogue search and per-device resolution of short-lived audio streams.
- Supabase-backed two-member rooms, room-scoped chat, revision-checked playback commands, Realtime delivery and REST recovery.
- Bounded new-track readiness/start handling, local fallback, drift correction and room diagnostics.
- GitHub-release updater with download digest, package/version and signing-identity validation.
- CI for Android lint, JVM unit tests, debug APK assembly, release-publishing guard, and the browser room test harness.

## Known limits and unverified behaviour

- No timestamped two-physical-device acceptance evidence is recorded. Actual sync latency/drift, screen-locked operation, interruptions and reconnect behaviour remain UNVERIFIED.
- The room identity is anonymous and device-local, not a durable account allowlist. Clearing app data can lose it.
- Chat is protected by room membership/RLS but is not end-to-end encrypted; the UI currently loads only the latest 100 messages.
- YouTube extraction depends on an unofficial upstream extractor and needs real-device/network testing.
- Review NewPipe Extractor's GPL-3.0-or-later obligations and choose an application licence before public distribution.

## Verification gates

1. Run Android lint, the complete unit-test suite, debug APK assembly and the release-publishing guard on the exact candidate commit.
2. Run the web-room-test build when room transport/client code changes.
3. Install and test the candidate APK for search, local/background playback, MediaSession controls, updater and room UI.
4. Complete the two-phone checklist in [ROOM_SYNC_RELIABILITY_PLAN.md](ROOM_SYNC_RELIABILITY_PLAN.md), recording diagnostics from both devices.
5. Do not claim device-level sync or release readiness from CI alone.
