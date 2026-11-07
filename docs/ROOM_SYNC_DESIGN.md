# Nexus room synchronization

This document describes the current implementation, not a separate architecture proposal. See [ARCHITECTURE.md](ARCHITECTURE.md) for ownership and [ROOM_SYNC_RELIABILITY_PLAN.md](ROOM_SYNC_RELIABILITY_PLAN.md) for acceptance measurements.

## Shared timeline

The Postgres `room_playback` row is the ordering authority. Each local action is published with the revision it was based on; compare-and-swap rejects stale writes. A receiver applies newer revisions only and never treats a transport wake-up as permission to overwrite a newer local action.

Each phone stores the canonical source URL and independently resolves an expiring stream URL through NewPipe. The stream URL is never included in the room row or Realtime event.

## Start and recovery

A new TRACK is prepared paused. Ready participants receive one shared server start timestamp. The controller uses a bounded total readiness window; it does not wait indefinitely for the peer. If the local stream is ready but the peer is not, the ready phone starts locally and publishes a RECOVERY revision anchored to its actual fallback start. A late peer resolves the same canonical item and catches up from that newer revision.

PLAY on an already prepared item resumes locally; it must not wait for new-track stream readiness. PAUSE and SEEK are ordinary revisioned commands.

## Drift

The controller projects position from a calibrated server-time anchor and uses the device monotonic clock for elapsed time. It distinguishes desired playback state from actual audio advancement and never corrects while buffering. A filtered dead zone and RTT-aware thresholds prevent chasing network jitter; sustained drift can use small speed changes or a guarded seek.

This is timeline synchronization, not sample-accurate output synchronization. Differences in extraction, decoder startup, network buffering and device audio output remain measurable through physical testing.
