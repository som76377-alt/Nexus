# Nexus target architecture

## Goal

Keep Nexus as a small native Android app with one Media3 player, one room session coordinator, explicit backend/player boundaries and testable synchronization policy. This is a staged refactor, not a rewrite.

## Runtime ownership

```text
MainActivity / ListeningRoomActivity
          | observe state + submit user intent
          v
PlaybackService
  ├── ExoPlayer (the only audio engine)
  ├── MediaSession
  └── RoomController (one active room session)
        ├── RoomRepository
        │     ├── Supabase REST/RPC snapshot + writes
        │     └── Supabase Realtime invalidation signals
        ├── RoomPlayerPort -> same ExoPlayer
        ├── RoomClock -> elapsedRealtime + calibrated server time
        └── Sync policy -> revision checks, retry, drift and recovery

Separate concerns:
  ├── catalog/   YouTube search, NewPipe resolution, canonical IDs/URLs
  ├── update/    signed GitHub release discovery, verification, install handoff
  └── web-room-test/ backend participant and diagnostics only
```

The Activity must not own long-lived synchronization jobs. The service owns room synchronization while the process/service is alive. Android may still destroy a paused service after an extended idle period; v1 accepts that limitation and reconciles when the app or media session is next activated. A remote PLAY wake-up after process destruction is not promised.

## Package boundaries

| Area | Responsibility | Must not own |
|---|---|---|
| `catalog` | Search, canonical source identity, NewPipe extraction | Room membership or sync |
| `playback` | ExoPlayer, MediaSession, playback lifecycle | Supabase schema details |
| `rooms/domain` | Commands, snapshots, controller and ports | Android/Media3/network imports |
| `rooms/data` (next) | Supabase REST/RPC and Realtime adapters | UI or player lifecycle |
| `rooms/sync` (next) | Drift policy, clock projection, reconciliation | Direct network or Activity references |
| `rooms/ui` (current Activity) | Room entry, chat presentation, controls, state rendering | Heartbeat, retry, sync or drift jobs |
| `update` | Release metadata, signer/hash checks, installer handoff | Room synchronization |
| `web-room-test` | Browser-side backend test participant | Claiming Android audio synchronization |

The existing source files are not all moved in this foundation commit. Move them only with focused compile/test checkpoints; do not combine a large package move with synchronization behavior changes.

## Room command and state path (v1)

1. Local user intent is applied to the local player immediately.
2. A command is created with a unique action UUID, origin device ID and the snapshot revision the user acted against.
3. The client publishes through the membership-checked RPC using revision compare-and-swap.
4. The database is authoritative. If the revision conflicts, fetch the newest snapshot and reconcile without overwriting a newer local action.
5. Realtime Postgres Changes are wake-up/invalidation signals, not the command payload or source of truth.
6. A notification causes a fresh snapshot read; only a newer revision is considered.
7. REST polling remains a bounded fallback for missed events and reconnect.
8. Chat updates never cause playback reloads or seeks.

## Track identity

- Persist/share the canonical YouTube video URL and stable source ID.
- Each device resolves its own short-lived audio stream through NewPipe.
- Never share or persist extracted stream URLs as room state.
- If the same canonical track is already loaded, update play/seek state without replacing the MediaItem.

## Time and drift

- Use `SystemClock.elapsedRealtime()` for local monotonic timing.
- Estimate server time using the existing five-probe/lowest-RTT calibration.
- Project playback from the last authoritative anchor.
- Keep buffering separate from real playback progress.
- Keep the existing filtered drift, latency-aware thresholds, speed cap and seek gate until physical measurements justify changes.
- Track timeline drift separately from send-to-peer latency, buffering duration and reconnect duration.

## Transport decision

V1 retains the existing Supabase Postgres Changes + REST snapshot + RPC compare-and-swap architecture. Do not add a second command-ordering system.

Private Realtime Broadcast is a possible later optimization only if measured command latency or missed-event behavior justifies it. Before adoption, verify the exact membership schema and user identity, configure private channels and RLS for `realtime.messages`, and prove an unauthorized client cannot subscribe. Do not switch clients to `private: true` before the matching server policies and all clients are ready.

Lamport ordering is not part of v1. It is unnecessary while the authoritative database revision CAS is the ordering mechanism. If the transport is redesigned later, specify concurrent-tap semantics and durable counters before implementation.

## Background lifetime

- Media3 MediaSessionService owns audio playback.
- RoomController is also owned by PlaybackService, not by an Activity.
- V1 does not promise remote wake after Android has destroyed the process/service.
- `onPlaybackResumption` restores playback when Android requests it; it is not a remote push wake mechanism.
- FCM high-priority data messages are a future opt-in only if remote wake becomes a required product behavior.

## Module strategy

Keep the existing single `:app` Gradle module during the foundation and first controller migration. The current codebase is small; splitting into Gradle modules now would add build configuration and dependency wiring without yet providing a measurable benefit. Package boundaries and interfaces are established first. Consider Gradle modules only after the domain/data/player boundaries are stable and independently tested.

## Non-goals for this stage

- No new audio engine, server, queue system or external room daemon.
- No supabase-kt dependency; keep the existing OkHttp client.
- No rewrite of catalog, updater or UI.
- No speculative deletion of existing migrations or compatibility workers.
- No promise of sample-accurate or zero-latency playback.
