# Open-source room and playback integration review

Reviewed 5 November 2026 against the upstream repositories and their current architecture documentation.

## Decision

Keep Nexus's existing native Android playback engine (Media3/ExoPlayer), NewPipe-based local resolver, and Supabase room backend. Adopt the strongest parts of the native listening-room architecture reviewed below, but do not add a second room backend or a media-relay server to the current two-phone prototype.

The initial integration used a UI-side `RoomPlayerAdapter`. The current service-owned implementation uses `RoomPlayerPort` / `Media3RoomPlayerPort` and `RoomMediaResolver`: each phone owns one local player and resolves its own stream while room membership, chat, playback authority, revisions and drift policy remain separate.

## Candidate review

### NeriPlayer Listen Together — closest architectural fit

Upstream: https://github.com/cwuom/NeriPlayer  
Server template: https://github.com/TheSmallHanCat/NeriPlayer-LTW

The Android project has a dedicated native `:listentogether` module for HTTP/WebSocket transport, session recovery, control arbitration, and player integration. Its Worker server uses Cloudflare Durable Objects with SQLite storage. Its documented model includes a server-derived expected playback position, reconnect secrets, per-client event ordering, and optional sharing of the host's current stream candidates. A listener first resolves on its own device and can use the host's session-only candidates as a fallback.

This is the best design reference for Nexus because it keeps each phone's own player and decoder while giving the room one authoritative timeline. We are adopting those design patterns rather than importing the upstream Android module verbatim. The NeriPlayer Android project is GPL-3.0; Nexus does not currently declare a project-wide licence, so copying that module would require an explicit licensing decision and attribution review. The standalone Worker template does not expose a separate licence file, so its implementation is not copied.

Cloudflare's current Free plan includes SQLite-backed Durable Objects, but it has daily request, compute, and storage limits. Free-plan overages fail rather than silently becoming unlimited capacity. That makes it a possible future hosting option, not a guarantee of unlimited forever-free service.

### ShareAux — useful only if Nexus later needs a common relayed audio stream

Upstream: https://github.com/Protomothis/ShareAux

ShareAux's server uses FFmpeg to create fragmented MP4/AAC chunks and sends them as binary WebSocket frames. Its current client appends those chunks to a browser Media Source Extensions buffer. It is not a drop-in Android Media3 library: Nexus would need a separate WebSocket-backed Media3 data source and a connection to ShareAux's own room/auth/queue APIs. The server also requires Node 22+, PostgreSQL, FFmpeg, and a media resolver. It is AGPL-3.0, and the operator is responsible for rights to content served by a self-hosted instance.

This could be revisited if physical tests show that independent local stream resolution is the root cause and Nexus needs a single common server-relayed audio stream. It is not included in the current app because it adds another server, transcoding, bandwidth, and a second room/session system.

### Zoff — strong complete platform, too heavy for this app

Upstream: https://github.com/zoff-music/vibes-backend

The current Zoff backend uses Go, PostgreSQL, Redis, versioned REST APIs, and replayable SSE. It coordinates shared queues, chat, room permissions, and playback, but playback still uses official provider players; it does not host or relay audio. Adopting it would replace Nexus's existing Supabase room backend and require a separate PostgreSQL/Redis deployment and a new client contract. That is unnecessary for a private two-person Android app.

## Nexus implementation

- Retain one authoritative playback snapshot in Supabase.
- Use monotonic revisions and compare-and-swap so a stale command cannot overwrite a newer room action.
- Estimate server time from a measured request midpoint and project the expected position locally.
- Apply drift correction with hysteresis and bounded speed changes/seeks; do not echo correction seeks as user actions.
- Use Supabase Realtime as the fast notification path and authenticated REST reads/polling as the recovery path.
- Give each device a replaceable player adapter and source resolver.
- Retry a failed local source load with bounded backoff; a newer room revision supersedes the old retry.
- Reclaim a member seat after a missed-heartbeat grace period and support explicit room exit.

## Deliberately not claimed

This is timeline synchronization, not sample-accurate audio clock synchronization. Device output latency, network jitter, stream differences, and provider availability still require two-phone tests. A successful Android CI build does not prove audible synchronization.
