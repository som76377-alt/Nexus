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

## Android prototype

The main Nexus screen now opens a Listening Room prototype. It includes device-local anonymous sign-in with an Android Keystore-encrypted session, Create Room and Join by Code, room-scoped chat, a 25-second heartbeat, shared YouTube URL playback through the existing Media3 service, and play/pause/seek synchronisation.

The first client version polls the room API about every 1.8 seconds instead of maintaining a persistent Realtime socket. The database publication is already enabled for a later transport upgrade. The server cleanup still removes rooms after one hour of inactivity, with the scheduled job running every five minutes.

Anonymous sign-ins must be enabled in the Nexus Supabase project's Authentication settings. If disabled, the app displays that setup requirement. Anonymous identity survives normal app restarts but cannot be recovered after app data is cleared or the app is reinstalled.

The backend currently limits each room to two members; it does not yet enforce a project-wide allowlist of the two intended people. A durable sign-in and global two-account allowlist remain follow-up hardening work. Two-device end-to-end validation is still required.

A Supabase anonymous user is device-local and cannot recover its identity after app data is cleared or the app is reinstalled. For a durable two-person private app, a permanent sign-in method may be preferable.
