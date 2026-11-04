# Nexus listening-room backend

This folder contains the database migrations for Nexus's private two-person listening rooms.

## Data model

- `rooms`: one row per room. Only a SHA-256 hash of the one-time invite code is stored.
- `room_members`: membership scoped to a room. The database serializes joins against the room row and rejects a third member.
- `room_messages`: every message has a required `room_id`; chat history is therefore isolated by room. Messages are readable only by members of that room.
- `room_playback`: one current playback snapshot per room. It stores the canonical source identity/URL, not a temporary extracted audio-stream URL.

All four exposed tables have RLS enabled. Room creation, joining, and activity heartbeats are performed through narrowly scoped authenticated RPC functions. No service-role key is used by the Android client.

## Inactivity expiry

A room's `last_activity_at` is refreshed by:
- room creation and successful joining;
- a member heartbeat through `touch_room(room_id)`;
- a chat message insert;
- a playback-state insert or update.

A scheduled `pg_cron` job runs every five minutes. It deletes rooms whose last activity is more than one hour old. Foreign-key cascades delete that room's members, messages, and playback snapshot together. Thus, the cleanup is room-scoped and does not mix or partially retain chat history.

The Android room screen must call `touch_room` periodically while a room is open (for example every 30 seconds), and stop heartbeats after Exit Room. A backgrounded/disconnected room will naturally expire after an hour without activity.

## Realtime

The messages, playback snapshot, and membership tables are included in the `supabase_realtime` publication. Clients must subscribe with the authenticated user's session; RLS controls which room rows are visible.

## Android integration still required

1. Enable anonymous sign-ins in Supabase Auth (or choose a durable sign-in method).
2. Add the Supabase Kotlin Auth, PostgREST, and Realtime clients.
3. Persist the anonymous session securely so each phone keeps its own identity after app restarts.
4. Build the Create Room / Join by Code / room chat UI.
5. Connect playback events to the existing Media3 `PlaybackService`, and periodically publish/consume playback snapshots with drift correction.
6. Add integration tests using two authenticated test users.

A Supabase anonymous user is device-local and cannot recover its identity after app data is cleared or the app is reinstalled. For a durable two-person private app, a permanent sign-in method may be preferable.
