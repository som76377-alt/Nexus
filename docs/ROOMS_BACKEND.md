# Nexus room backend

The private two-person room backend uses the existing Supabase Postgres project, Auth REST endpoints, RPCs and authenticated Realtime WebSocket. The Android client uses the publishable key and device-local anonymous identity; no service-role key is embedded.

## Stored data

- `rooms`: room identity, hashed invite code, creator and activity timestamps.
- `room_members`: authenticated members and last-seen heartbeat.
- `room_messages`: room-scoped chat messages.
- `room_playback`: one authoritative playback snapshot per room, including canonical source, play state, saved position, revision and action metadata.
- `private.room_join_attempts`, readiness and start tables: server-side rate-limit and synchronized-start coordination data.

Public room tables use RLS and membership checks. Each room accepts at most two active members. New invites use five human-friendly characters; legacy 16-character hex codes remain accepted. Only invite-code hashes are stored.

## Playback writes and events

- Client writes go through `publish_room_playback`, which validates membership and performs revision compare-and-swap.
- Action IDs make commands identifiable. A stale revision is not blindly replayed over a newer state.
- Private Realtime broadcasts carry room-scoped playback snapshots and scheduled-start signals. The client validates room/revision and uses REST reads to reconcile after reconnect or missed signals.
- Chat inserts are separate signals and never trigger playback application.
- Each Android device resolves the canonical track locally. Expiring stream URLs never enter shared room state.

A new playing TRACK can use the database readiness/start anchor. The wait is bounded on the client; a ready device starts locally if its peer does not become ready, then publishes a RECOVERY revision so a late peer joins the current timeline. A PLAY on an already prepared track does not re-enter the new-track barrier.

## Presence and expiry

The Android room controller touches the room every 25 seconds. A member who stops heartbeating becomes stale and can be reclaimed after the backend grace period. Room access expires after one hour of inactivity; the scheduled cleanup physically deletes expired rooms within the cron interval. Explicit Exit calls `leave_room`.

## Security and maintenance

- Refresh/access tokens are encrypted at rest with Android Keystore AES-GCM.
- Chat is protected by membership RLS but is plaintext in the database; it is not end-to-end encrypted.
- Anonymous identity is device-local and may be lost after uninstall or clearing app data.
- Never reset RLS or edit a migration already applied to the live project. Schema changes must be new forward-only migrations.
- Do not claim two-phone reliability until acceptance has been completed on physical devices.
