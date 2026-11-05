# Nexus room synchronization design

## Chosen foundation

Nexus keeps its existing native Android player and Supabase project. The room protocol follows the useful architecture patterns identified in Zoff: a server-authoritative shared room state, revisions for ordering, an event channel for low-latency updates, and a provider/player boundary on each client.

The implementation does not embed or deploy the whole Zoff backend. Doing so would introduce a separate Go/Postgres/Redis service stack and a second room identity model when Nexus already has a healthy Supabase database and authenticated Realtime. Instead, Nexus keeps Supabase Postgres/RLS as its source of truth and uses its Realtime WebSocket as the fast signal path.

## Device-side adapter boundary

Each Android device has two replaceable ports:

- `RoomMediaResolver` maps a canonical track reference to a fresh playable stream on that device.
- `RoomPlayerAdapter` exposes local player state and controls to the room coordinator.

The current implementations are `YoutubeRoomMediaResolver` (NewPipe) and `PlaybackConnection` (Media3/ExoPlayer). A future Android player or catalogue provider can implement these ports without changing chat, presence, invite codes, room state, or synchronization policy.

Both devices must still resolve the same canonical track. Merely changing the player engine cannot make a phone's private local file available to the other phone; that would require a separate audio-sharing or file-transfer feature.

## Room event and recovery model

1. The server stores one authoritative playback snapshot per room with a monotonic revision and action ID.
2. A client publishes a deliberate action using compare-and-swap against the revision it last observed.
3. If the CAS loses, the older command is discarded and the latest server snapshot wins. It is never replayed on top of the newer revision.
4. Realtime messages are wake-up signals only; the client re-reads the row over authenticated REST.
5. REST polling repairs missed events and covers temporary WebSocket outages.
6. Every phone independently resolves the shared canonical source and plays it through its own player adapter.
7. Clock calibration, position projection, drift hysteresis, bounded source-load retry and rate-limited local correction keep recovery from repeatedly restarting both phones.

This is timeline synchronization, not sample-accurate audio clock synchronization. Network jitter, source differences, decoder startup and device audio output latency can still cause small audible offsets. Two-device testing is the release gate.

## Why ShareAux audio relay is not the current choice

ShareAux's shared-audio path is designed around server-generated media segments and browser Media Source Extensions. Nexus is a native Android Media3 app. Reusing that path would require a separate Android-compatible stream transport and more server bandwidth/CPU, rather than simply wiring in an Android player. It remains a possible future option if Nexus decides to relay one common audio stream rather than let each phone resolve the same source independently.

## Backend extensibility

Room membership, chat, playback snapshots and the player/provider ports remain separate concerns. Future changes such as playlists, host permissions, additional providers, richer presence, or a different transport can be added without coupling those features to Media3 implementation details.
