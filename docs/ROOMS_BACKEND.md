# Nexus listening-room backend

This folder contains the database migrations for Nexus's private two-person listening rooms.

## Data model

- `rooms`: one row per room. Only a SHA-256 hash of the invite code is stored.
- `room_members`: membership scoped to a room. The database serializes joins against the room row and rejects a third member.
- `room_messages`: every message has a required `room_id`; chat history is therefore isolated by room. Messages are readable only by members of that room.
- `room_playback`: one authoritative playback snapshot per room. It stores the canonical source identity/URL, explicit action metadata, a monotonic revision, and the command timestamp—not a temporary extracted audio-stream URL.

All four public room tables have RLS enabled. The Android-facing table grants are limited to the exact operations required: room/member reads, message reads/inserts, and playback reads/inserts/updates. Room creation, joining, and activity heartbeats are performed through authenticated RPC functions. No service-role key is used by the Android client. The internal `private.room_join_attempts` table has no grants to `anon` or `authenticated` and the `private` schema has no client USAGE grant; Supabase's advisor still flags its RLS-disabled state, so that finding remains documented for review.

## Inactivity expiry

A room's `last_activity_at` is refreshed by:
- room creation and successful joining;
- a member heartbeat through `touch_room(room_id)`;
- a chat message insert;
- a playback-state insert or update.

After one hour of inactivity, the join RPC, heartbeat, chat, and playback RLS policies reject further room use. A scheduled `pg_cron` job runs every five minutes and physically deletes rooms whose last activity is more than one hour old. Foreign-key cascades delete that room's members, messages, and playback snapshot together. Physical deletion can therefore occur up to about five minutes after the one-hour threshold.

The Android room screen calls `touch_room` every 25 seconds while a room is open. Explicit Exit Room calls `leave_room` to release the current seat immediately. If an app is killed, uninstalled, loses its device-local anonymous identity, or cannot heartbeat, a later join prunes that member after a three-minute stale-seat grace period. A room with no activity still expires after one hour.

## Realtime

The messages, playback snapshot, and membership tables are included in the `supabase_realtime` publication. Clients must subscribe with the authenticated user's session; RLS controls which room rows are visible.

## Android prototype

The main Nexus screen now opens a Listening Room prototype. It includes device-local anonymous sign-in with an Android Keystore-encrypted session, Create Room and Join by Code, room-scoped chat, a 25-second heartbeat, shared YouTube URL playback through the existing Media3 service, and play/pause/seek synchronisation.

The current client subscribes to authenticated Supabase Realtime Postgres Changes for its room's playback row and chat inserts. A Realtime event is only a signal to re-read the authoritative row over REST; it is not trusted as a command by itself. REST polling remains as a fallback (faster while the socket is disconnected, slower while it is healthy), and the subscription reconnects with bounded exponential backoff. The database write uses a compare-and-swap revision check so a delayed request cannot silently overwrite a newer room command. On a CAS conflict the client now adopts the latest server state instead of retrying its older command. A failed remote stream load is retried with bounded backoff, while a newer revision bypasses the old retry timer. The chat UI displays the latest 100 messages in chronological order; older messages remain stored until room expiry but are not yet paginated in the UI.

Anonymous sign-ins must be enabled in the Nexus Supabase project's Authentication settings. If disabled, the app displays that setup requirement. Anonymous identity survives normal app restarts but cannot be recovered after app data is cleared or the app is reinstalled. The app does not silently create a replacement identity if the saved session cannot be refreshed or decrypted, because doing so could strand the old room memberships.

The backend currently limits each room to two members; it does not yet enforce a project-wide allowlist of the two intended people. A durable sign-in and global two-account allowlist remain follow-up hardening work. Room messages are protected by membership RLS but are stored as plaintext; they are not end-to-end encrypted. Two-device end-to-end validation is still required.

A Supabase anonymous user is device-local and cannot recover its identity after app data is cleared or the app is reinstalled. For a durable two-person private app, a permanent sign-in method may be preferable.


## Playback synchronization and recovery

- Playback commands are classified as `TRACK`, `PLAY`, `PAUSE`, or `SEEK` using Media3's player event reasons. Buffering and audio-focus interruptions are not published as user pauses.
- The desired room play state uses Media3's `playWhenReady`, not `isPlaying`. This prevents a local buffering state from being mistaken for a deliberate pause.
- A Realtime event causes the receiver to fetch the latest row, resolve the canonical track, prepare it without audible early playback, project the expected position using the server timestamp, seek once if necessary, and then apply the desired play state.
- Ongoing drift correction uses a dead zone, repeated-sample hysteresis, gentle 0.98x/1.02x speed correction, and a five-second seek cooldown. No correction is made while the local player is buffering. A single lagging phone is corrected locally; the healthy phone is not reloaded.
- The synchronizer does not repeatedly restart both players. This is intentional: reloading the healthy device in response to the other device's buffer event can create an oscillating correction loop.
- Realtime transport, clock estimation, and drift policy still require two-physical-device validation under real Wi-Fi/mobile-network conditions. No claim of perfect sample-accurate synchronization is made.
