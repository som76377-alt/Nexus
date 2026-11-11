# Nexus architecture

Nexus is a native Android music player with private two-person listening rooms. This file is the current architecture reference; see [PROJECT_MAP.md](PROJECT_MAP.md) for file locations and [FOUNDATION_STATUS.md](FOUNDATION_STATUS.md) for branch/verification state.

## Runtime ownership

- `PlaybackService` owns the single Media3 ExoPlayer, MediaSession and active `ServiceRoomController`.
- `MainActivity` and `ListeningRoomActivity` connect through MediaController. They render state and send user actions; they do not own long-lived playback synchronization.
- `Media3RoomPlayerPort` applies remote room state to that same player. `RoomIntentForwardingPlayer` captures explicit local MediaSession actions and prevents remote corrections from echoing as user commands.
- `ServiceRoomController` owns the room session, server clock, revision reconciliation, local command publication, readiness/start handling, bounded recovery, drift correction and diagnostics.
- `RoomsApi` owns the authenticated Supabase REST/RPC client and encrypted anonymous session. `RoomRealtimeClient` owns the authenticated Phoenix WebSocket.

## Data flow

```text
Activity -> MediaController -> PlaybackService
                               |-- one ExoPlayer + MediaSession
                               |-- Media3RoomPlayerPort
                               |-- ServiceRoomController
                                      |-- RoomRepository -> RoomsApi
                                      |-- RoomClock -> Supabase server time
                                      |-- RoomMediaResolver -> NewPipe
                                      |-- drift/revision/recovery policy
```

Catalogue search and stream resolution are in `catalog/`. The shared room state contains canonical source identity only; each device resolves its own short-lived stream URL. Never save or broadcast an extracted stream URL.

## Room protocol

- The Postgres playback row and its monotonically increasing revision are authoritative.
- Writes use the membership-checked `publish_room_playback` RPC with compare-and-swap and an action ID.
- Realtime carries room-scoped playback snapshots and scheduled-start events for prompt application. REST snapshot reads remain the reconnect/reconciliation fallback.
- Chat inserts are separate from playback reconciliation.
- A new playing track uses a bounded readiness/start window. A prepared-track PLAY resumes locally without waiting for the new-track barrier.
- If a new track cannot complete the shared start within the bound, a ready local player starts and publishes a RECOVERY anchor so a late peer can join the current timeline.
- Drift is corrected locally with a dead zone, filtered samples, bounded speed changes and a seek gate. This is timeline synchronization, not sample-accurate audio synchronization.

## Deliberate constraints

- One audio engine only; no second player, audio relay server or extra backend.
- Keep the current OkHttp, coroutines, Media3 and Supabase REST/Realtime stack.
- Do not add FCM for remote process wake unless that becomes a real product requirement.
- Keep applied SQL migrations immutable. New schema changes require a new forward migration.
- Keep the release signer, APK digest, package/version and trusted-download checks.
- WorkManager compatibility code remains until jobs scheduled by older app versions are safely expired.

## Verification

CI proves compilation, lint, unit tests and the configured build checks for one commit. It does not prove stream extraction, device background behavior or two-phone synchronization. Those require physical-device tests.
