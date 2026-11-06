# Room synchronization policy

This package will contain player- and transport-independent synchronization policy.

Planned responsibilities:

- Project playback position from a server-time anchor and monotonic local time.
- Decide whether drift is ignored, corrected by a small speed adjustment, or requires a guarded seek.
- Reject stale snapshots and protect newer local intent from older asynchronous work.
- Define retry/backoff and reconnect reconciliation policy.
- Expose deterministic policy functions that can be tested without Android or a network.

Rules:

1. Keep the database revision as the v1 ordering authority.
2. Never correct drift while buffering or while a previous seek is unresolved.
3. Do not turn chat, presence or queue events into playback commands.
4. Keep send-to-peer latency, timeline drift, buffering duration and reconnect duration as separate measurements.
5. Preserve existing tested `RoomPlaybackMath` behavior until equivalent tests pass after any move.
6. No direct Supabase, OkHttp, Media3, Activity or Android UI imports.
