# Nexus implementation status

Repository: `som76377-alt/Nexus`.

## Branch safety

- `main` remains the release baseline.
- PR #10 remains the separate room-playback reliability change.
- The simplification work is isolated on `refactor/nexus-simplification`, based on the current reliability branch tip `550774758aa018d2f391ff30c0398aeb150a32be`.
- This branch is a refactor candidate only. It is not merged and does not change the live Supabase project.

## Current implementation

- One service-owned ExoPlayer and MediaSession.
- Service-owned room controller, player/repository/clock ports and local NewPipe stream resolution.
- Supabase RPC compare-and-swap writes, room-scoped Realtime, REST recovery, chat, member heartbeat and inactivity expiry.
- New-track readiness/start handling, immediate resume for prepared tracks, bounded local fallback and RECOVERY anchor.
- Room drift filtering, speed correction, seek gate, diagnostics and deterministic JVM tests.
- APK update verification checks package identity, version, digest and signing identity before install handoff.

## Simplification pass

The first batches remove unused player and room command APIs, duplicate retry/clock wrappers and timestamp parsing, factor repeated MediaSession skip bookkeeping, avoid idle playback UI polling, reduce healthy browser polling, and avoid repeated PackageManager lookups in one update check.

The pass intentionally keeps concurrency guards, revision CAS, clock calibration, recovery anchors, the one-player design, updater security checks, the old-job cancellation worker and the applied migration history.

## Release gates

1. Fresh Android lint, unit tests and debug APK build on the exact final commit.
2. Web room test harness build on the exact commit.
3. Install the APK and verify local playback, background playback, MediaSession controls, update checking and room entry.
4. Test two real phones for new track, pause, resume, seek, buffering, rapid commands, reconnect and five-minute screen lock.
5. Only merge after the refactor diff and all checks are reviewed. CI cannot substitute for physical playback logs.
