# Nexus architecture

## Product direction

Nexus is intended to combine music/video discovery with a first-class background player and social listening. The UI must not own playback state: the playback service is the source of truth for the current item, queue, play state, and position.

## Initial Android structure

- `MainActivity`: application entry screen only.
- `playback/PlaybackService`: Media3 `ExoPlayer` and `MediaSession`, hosted in `MediaSessionService`.
- `playback/PlaybackConnection`: Activity-side `MediaController` bridge that sends resolved streams to the service.
- `playback/PlaybackRequest`: short-lived stream handoff; canonical video identity stays separate from the expiring stream URL.
- `catalog/` (planned): provider-neutral catalogue interfaces and provider adapters.
- `rooms/` (planned): listening-room state, chat, and synchronization client.

## Catalogue boundary

Do not call an extractor directly from a composable or Activity. Add a provider interface such as:

- `search(query, page)`
- `getDetails(contentId)`
- `resolvePlayableSource(contentId, preferredFormat)`

The NewPipe adapter should translate extractor models into Nexus-owned models. This keeps the rest of the app independent of provider-specific response formats and makes it possible to add or replace sources.

Unofficial extractors are maintenance-sensitive. Keep provider code isolated, pin dependency versions, log extraction failures without recording private user data, and add integration tests for representative searches and stream resolution.

## Playback boundary

The app UI connects to `PlaybackService` using a Media3 `MediaController`. Selecting a search result resolves a fresh audio URL, maps it to a Media3 `MediaItem` with title, artist and artwork metadata, then sends it through the controller. The service owns ExoPlayer and MediaSession; a persistent mini-player uses the same controller for play/pause and seeking. Audio focus and noisy-route handling are enabled. Playback must be tested with the screen locked, while switching apps, after audio focus changes, and after service/process recreation.

## Social listening model

A room server should coordinate metadata and playback commands, not relay raw audio by default. The room state should include:

- room identifier and participant membership
- current content identifier and queue revision
- host/controller identity
- playing/paused state and authoritative position timestamp
- playback speed and a monotonically increasing event sequence

Clients resolve and play media locally, then correct drift against the room clock. The server must handle late joins, host departure, reconnects, stale commands, and content that cannot be resolved on one participant's device.

The backend choice is intentionally postponed until the room protocol and expected scale are clearer. Compare free-tier hosted realtime services with a small WebSocket service before selecting one.

## Verification gates

1. Debug build resolves dependencies.
2. Extractor can search and resolve a known public test item.
3. Media3 can play a resolved stream in the foreground through the controller/service boundary.
4. Playback survives app switching and screen lock on a physical device, with notification controls and audio focus behaving correctly.
5. Queue, audio focus, notification controls, and reconnect behaviour are tested.
6. Only then build the full catalogue UI and social rooms.
