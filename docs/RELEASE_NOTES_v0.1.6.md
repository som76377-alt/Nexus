# Nexus v0.1.6

This release prepares Nexus for two-device physical testing.

## Room and playback updates
- Generate short five-character room invite codes while continuing to accept legacy room codes.
- Add authenticated Supabase Realtime playback-row notifications with REST polling fallback and reconnect backoff.
- Make playback writes revision-checked and idempotent so stale updates cannot overwrite newer room commands.
- Track intentional track, play, pause, and seek actions separately from local buffering interruptions.
- Add server-clock calibration and timestamp-based playback position projection.
- Add guarded drift correction with a dead zone, repeated-sample hysteresis, gentle speed adjustment, and seek cooldown.
- Recover a buffering receiver locally instead of repeatedly restarting the healthy device.

## Validation
- Android lint passed on the release commit.
- Unit tests passed on the release commit.
- Debug APK assembled successfully.
- Signed release APK passed signing-certificate, package-name, version, and checksum verification.

## Physical test status
Two-device playback and real network interruption behaviour still require testing on physical Android phones. This release is a test candidate, not a claim of production-grade or sample-accurate synchronization.
