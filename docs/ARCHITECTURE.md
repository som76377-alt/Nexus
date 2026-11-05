# Nexus architecture

## Product direction

Nexus is a native Android music-discovery and social-listening prototype. The playback service owns playback state; Activities observe and command it through a Media3 `MediaController`. The UI must never become the source of truth for the active item, queue, playback state, or position.

## Implemented source layout

```text
app/src/main/
  AndroidManifest.xml
  java/com/nexus/music/
    MainActivity.kt                 # Search screen, mini-player, updater, room entry
    CatalogFormatting.kt            # Duration and view-count formatting
    catalog/
      CatalogModels.kt              # App-owned catalogue and resolved-audio models
      ExtractorRuntime.kt           # One-time NewPipe process initialization
      OkHttpDownloader.kt           # NewPipe Downloader -> OkHttp transport
      AudioStreamSelector.kt        # Validated HTTP(S) stream selection
      YoutubeCatalogRepository.kt   # YouTube search and stream resolution
    playback/
      PlaybackService.kt            # ExoPlayer + MediaSession lifetime
      PlaybackConnection.kt         # Activity-side MediaController bridge
      PlaybackRequest.kt            # Short-lived metadata/stream handoff
    rooms/
      RoomsApi.kt                   # Supabase Auth/REST client and encrypted session
      ListeningRoomActivity.kt      # Create/join, chat, heartbeat, playback sync
    update/
      GitHubReleaseUpdater.kt       # Release metadata, APK integrity/signature checks
      NexusUpdateInstaller.kt       # Android PackageInstaller hand-off
      NexusUpdateScheduler.kt       # Cancels legacy silent background jobs
      UpdateInstallStatusActivity.kt
      ...
  res/
  test/
    java/com/nexus/music/
      CatalogFormattingTest.kt
      catalog/
      playback/
      update/
```

## Catalogue

`YoutubeCatalogRepository` is currently the only provider adapter. It runs synchronous NewPipe extraction on `Dispatchers.IO`, maps extractor results into Nexus-owned models, and resolves a fresh playable URL just before playback. `ExtractorRuntime` configures NewPipe's process-wide downloader once.

The current catalogue supports first-page YouTube search for all results, music songs, and videos. Room track resolution is behind `RoomMediaResolver`, so another provider can be added for listening rooms without changing the room state protocol. General catalogue search, pagination, playlist/channel browsing and shared caching remain separate work.

## Playback

`PlaybackService` owns ExoPlayer and MediaSession. It uses ExoPlayer's local wake mode so the CPU stays awake only while audio is ready/buffering and playback is requested. Media3 maintains the platform media notification and controls while the service is actively playing.

`PlaybackConnection` attaches a MediaController and forwards a resolved stream as a MediaItem. The canonical source URL is stored as the media ID; the expiring audio stream URL is used only for playback.

Resolved stream URLs may expire. Persist the canonical source URL (for example, the YouTube video URL) in any future history or playlist feature; resolve a new stream URL immediately before playback. Never persist a signed stream URL as durable content identity.

## Private listening rooms

The room backend is a Supabase Postgres project. It contains:
- `rooms`: hashed invite code, creator, creation and activity timestamps
- `room_members`: membership keyed by room and authenticated user
- `room_messages`: chat rows scoped to one room
- `room_playback`: one shared playback snapshot per room
- `private.room_join_attempts`: internal join-attempt rate-limit records

All public room tables have RLS. Access policies require room membership, sender identity is checked against `auth.uid()`, and table grants are narrowed to the operations used by the app. Invite codes are random 16-character hex values; only SHA-256 hashes are stored. A room accepts at most two members.

The Android client uses Supabase anonymous Auth through REST and encrypts the access/refresh session with an Android Keystore AES-GCM key. This identity is device-local: app data loss or reinstall can lose the identity. Anonymous sign-ins must be enabled in the Supabase project. The backend does not yet enforce a project-wide allowlist of the two intended people.

Chat and playback are scoped by room ID. The UI loads the latest 100 messages in chronological order. A heartbeat runs every 25 seconds while the room Activity is active. An authenticated Supabase Realtime WebSocket subscribes to room playback updates and chat inserts. REST remains authoritative: every event triggers a room-scoped read, and periodic polling repairs missed events while reconnect uses bounded exponential backoff. A temporary network error does not itself eject a member; only an explicit false membership heartbeat or room expiry exits the room.

Room synchronization is deliberately provider- and player-adapter based. `RoomMediaResolver` resolves a canonical room track reference into a fresh stream on each phone, and `RoomPlayerAdapter` exposes local player controls to the room coordinator. The current adapters use NewPipe and the Media3/ExoPlayer service, but another Android player or catalogue adapter can be connected without rewriting room membership, chat, revisions, or drift policy. Each phone still has to be able to resolve the same canonical track.

Playback is server-authoritative with monotonically increasing revisions and compare-and-swap writes. A stale command that loses a revision race is never replayed over the newer room state. Both phones estimate the server clock and project the expected position; sustained drift is corrected locally with hysteresis, gentle speed changes, and rate-limited seeks. A local drift-correction seek is not reclassified as a user seek. Failed remote source resolution is retried with bounded backoff, while a newer room revision is applied immediately. This reduces feedback loops but does not promise sample-accurate or zero-latency synchronization.

Room access is blocked after one hour of inactivity. The scheduled cleanup runs every five minutes and deletes the room, its chat, membership, and playback state by cascade. Physical deletion can therefore occur up to about five minutes after the one-hour threshold.

Room chat is protected by authenticated membership and RLS but is not end-to-end encrypted. Messages are stored as ordinary text in the database. Add client-side encryption before treating the room chat as confidential from the backend operator.

## UI and lifecycle

The current UI uses Android framework Views in `MainActivity` and `ListeningRoomActivity`, not Compose. Search requests are scoped to a generation so a late response cannot replace newer results. Activities cancel their coroutine scopes and release their MediaController connections on destruction. Playback continues in the service when the Activity stops.

## Not implemented

- Durable Google/email sign-in and global two-account allowlist
- End-to-end encrypted chat
- Persistent Realtime/WebSocket transport
- Full chat-history pagination and attachments
- Playlist/queue persistence, local library, offline caching, and media downloads
- Voice/video calling

## Verification gates

1. CI lint, unit tests, and debug APK assembly pass for the exact commit being tested.
2. Deterministic downloader and stream-selection tests pass without contacting YouTube.
3. The opt-in live extractor smoke test can search and resolve a public item when explicitly enabled.
4. A physical Android device can play a resolved stream through MediaController.
5. Background playback, screen lock, notification, lock-screen controls, audio focus, noisy-route, seek, and service-recreation behaviour are checked.
6. Two physical devices verify room creation/join, room-isolated chat, one-hour expiry, reconnect, and shared playback.
7. Before broader distribution, review the account allowlist, E2E encryption, privacy, and NewPipe GPL-3.0-or-later obligations.

A successful CI build does not prove that YouTube extraction or two-device playback works on every device or network.
