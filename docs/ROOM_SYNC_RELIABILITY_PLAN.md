# Nexus room synchronization reliability plan

## Product goal

Make two-device listening feel immediate and stable while keeping the Android app native, small, and usable on a broad range of Android phones. No software can guarantee zero latency on every device or network; the measurable goal is to prevent avoidable jumps, repeated seeks, stale commands, and battery-heavy polling, then validate real-device drift.

## Exact implementation stack

| Responsibility | Selected component |
| --- | --- |
| Audio engine | Existing AndroidX Media3 ExoPlayer |
| Background playback and system controls | Existing Media3 MediaSessionService |
| Live room events | Existing Supabase Realtime WebSocket / Postgres Changes |
| Authoritative room state | Existing Supabase Postgres row and `publish_room_playback` RPC |
| Command ordering | Existing revision compare-and-swap and idempotent action UUID |
| HTTP | Existing OkHttp |
| Async work | Existing Kotlin Coroutines |
| Time base | Android `SystemClock.elapsedRealtime()` anchored to Supabase server time |
| Drift control | Local Kotlin controller: EMA filtering, RTT-aware dead zone, proportional speed correction, confirmed hard seek |
| Recovery | Realtime signal coalescing plus REST fallback and room heartbeat |

No new runtime dependency is added. No second audio engine, Socket.IO server, Snapcast daemon, Capacitor wrapper, or additional database is introduced.

## Reference-project findings

- **Krovi Music:** useful to understand a compact event-driven room. Do not copy its full-room-state behavior: chat and queue changes can change the room timestamp and cause the client to re-seek unchanged playback. Its use of device wall clocks also makes the timeline sensitive to clock skew.
- **Music Together:** useful reference for sampling server time, smoothing drift, and allowing network latency to influence correction thresholds. Its Android implementation is AGPL-3.0; Nexus uses the design ideas only and does not copy its source.
- **NeriPlayer:** useful reference for native Android room/player separation. Its GPL-3.0 code is not imported.
- **Snapcast:** not selected because it distributes a central audio stream to synchronized receivers; Nexus needs each phone to resolve and play its own catalogue stream independently.

## Implementation rules

1. Only a change to the playback row is allowed to trigger playback reconciliation. Chat and queue events never become playback commands.
2. Realtime notifications are wake-up signals. The database row plus revision is authoritative.
3. A newer local user action must never be overwritten by an older in-flight extraction or remote apply.
4. Multiple realtime signals are coalesced; a newer signal aborts obsolete remote track loading.
5. Playback time is projected from a server-time anchor using a monotonic device clock after calibration.
6. Five short clock probes are sampled concurrently. The three lowest-RTT valid samples determine the clock offset and measured RTT.
7. Drift is filtered. Small jitter is ignored, moderate drift receives a capped pitch-preserving speed adjustment, and a hard seek requires repeated large drift plus a cooldown.
8. Buffering is not treated as playback progress and cannot trigger correction.
9. Realtime reconnect performs an immediate state reconciliation. REST polling remains a bounded safety net; chat fallback polling is slower while Realtime is healthy.
10. The existing Media3/OkHttp/Coroutines stack is retained to avoid unnecessary APK weight and compatibility surface.

## Compatibility boundary

The current app minimum is Android API 24 (Android 7.0). The implementation remains within that floor and uses Media3 commands only when available. "Every device" cannot be guaranteed: OEM power management, Bluetooth routes, codecs, audio focus, OS versions, and network quality differ. Device-matrix testing is required before claiming broad compatibility.

## Automated checks

- Run Android lint.
- Run all local unit tests, including clock estimator, drift filtering, RTT dead-zone, high-latency seek threshold, seek gate, and retry policy tests.
- Assemble the debug APK.
- Inspect the exact workflow run and its artifact for the exact PR head commit.

## Physical-device acceptance matrix

Use two different Android phones, preferably different Android versions and manufacturers:

1. Same track: create/join, start, pause, resume, seek, and change track in both directions.
2. Rapid controls: quick play/pause/seek/skip sequences; latest user intent must win without repeated seek/buffer loops.
3. Buffering: one device on a slower connection; it must catch up after buffering without publishing a false advancing timeline.
4. Network recovery: temporarily disable/re-enable Wi-Fi or mobile data; the returning device must reconcile to the newest revision.
5. Background behavior: lock screen, use media notification/system controls, switch apps, and return to the room.
6. Power/compatibility: test one lower-end phone and an older supported Android version.

Record command-to-remote-response time, steady-state drift, hard-seek count, reconnect time, crashes, and battery/network behavior. Automated CI does not replace these physical tests.
