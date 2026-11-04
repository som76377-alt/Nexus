# Nexus listening-room backend

This folder contains the database migrations for Nexus's private two-person listening rooms.

## Data model

- `rooms`: one row per room. Only a SHA-256 hash of the one-time invite code is stored.
- `room_members`: membership scoped to a room. The database serializes joins against the room row and rejects a third member.
- `room_messages`: every message has a required `room_id`; chat history is therefore isolated by room. Messages are readable only by members of that room.
- `room_playback`: one current playback snapshot per room. It stores the canonical source identity/URL, not a temporary extracted audio-stream URL.

All four public room tables have RLS enabled. The Android-facing table grants are limited to the exact operations required: room/member reads, message reads/inserts, and playback reads/inserts/updates. Room creation, joining, and activity heartbeats are performed through authenticated RPC functions. No service-role key is used by the Android client. The internal `private.room_join_attempts` table has no grants to `anon` or `authenticated` and the `private` schema has no client USAGE grant; Supabase's advisor still flags its RLS-disabled state, so that finding remains documented for review.

## Inactivity expiry

A room's `last_activity_at` is refreshed by:
- room creation and successful joining;
- a member heartbeat through `touch_room(room_id)`;
- a chat message insert;
- a playback-state insert or update.

After one hour of inactivity, the join RPC, heartbeat, chat, and playback RLS policies reject further room use. A scheduled `pg_cron` job runs every five minutes and physically deletes rooms whose last activity is more than one hour old. Foreign-key cascades delete that room's members, messages, and playback snapshot together. Physical deletion can therefore occur up to about five minutes after the one-hour threshold.

The Android room screen must call `touch_room` periodically while a room is open (for example every 30 seconds), and stop heartbeats after Exit Room. A backgrounded/disconnected room will naturally expire after an hour without activity.

## Realtime

The messages, playback snapshot, and membership tables are included in the `supabase_realtime` publication. Clients must subscribe with the authenticated user's session; RLS controls which room rows are visible.

## Android prototype

The main Nexus screen now opens a Listening Room prototype. It includes device-local anonymous sign-in with an Android Keystore-encrypted session, Create Room and Join by Code, room-scoped chat, a 25-second heartbeat, shared YouTube URL playback through the existing Media3 service, and play/pause/seek synchronisation.

The first client version polls the room API about every 1.8 seconds instead of maintaining a persistent Realtime socket, and backs off during network failures. The database publication is already enabled for a later transport upgrade. The chat UI displays the latest 100 messages in chronological order; older messages remain stored until room expiry but are not yet paginated in the UI.

Anonymous sign-ins must be enabled in the Nexus Supabase project's Authentication settings. If disabled, the app displays that setup requirement. Anonymous identity survives normal app restarts but cannot be recovered after app data is cleared or the app is reinstalled. The app does not silently create a replacement identity if the saved session cannot be refreshed or decrypted, because doing so could strand the old room memberships.

The backend currently limits each room to two members; it does not yet enforce a project-wide allowlist of the two intended people. A durable sign-in and global two-account allowlist remain follow-up hardening work. Room messages are protected by membership RLS but are stored as plaintext; they are not end-to-end encrypted. Two-device end-to-end validation is still required.

A Supabase anonymous user is device-local and cannot recover its identity after app data is cleared or the app is reinstalled. For a durable two-person private app, a permanent sign-in method may be preferable.
