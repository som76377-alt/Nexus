# Nexus architecture

## Product direction

Nexus is a native Android music-discovery and social-listening app. The playback service owns playback state; the UI observes and commands it through a Media3 `MediaController`. The UI must never become the source of truth for the active item, queue, playback state, or position.

## Implemented source layout

```text
app/src/main/
  AndroidManifest.xml
  java/com/nexus/music/
    MainActivity.kt                 # Native search screen and persistent mini-player
    CatalogFormatting.kt            # Compact duration and view-count formatting
    catalog/
      CatalogModels.kt              # App-owned catalogue and resolved-audio models
      ExtractorRuntime.kt            # One-time NewPipe process initialization
      OkHttpDownloader.kt            # NewPipe Downloader -> OkHttp transport adapter
      AudioStreamSelector.kt         # Validated HTTP(S) stream selection
      YoutubeCatalogRepository.kt    # YouTube search and stream-resolution boundary
    playback/
      PlaybackService.kt             # ExoPlayer + MediaSession lifetime
      PlaybackConnection.kt           # Activity-side MediaController bridge
      PlaybackRequest.kt              # Short-lived metadata/stream handoff
  res/                                # Manifest resources and theme

app/src/test/
  java/com/nexus/music/
    CatalogFormattingTest.kt
    catalog/
      AudioStreamSelectorTest.kt
      OkHttpDownloaderTest.kt
      YoutubeExtractorLiveSmokeTest.kt
```

### Catalogue

`YoutubeCatalogRepository` is currently the only provider adapter. It runs synchronous NewPipe extraction on `Dispatchers.IO`, maps extractor results into Nexus-owned models, and resolves a fresh playable URL just before playback. `ExtractorRuntime` configures NewPipe's process-wide downloader once.

The current catalogue supports first-page YouTube search for all results, music songs, and videos. Pagination, playlist/channel browsing, caching, and a provider-neutral interface are not implemented yet. Before another source is added, introduce a Nexus-owned provider interface so the UI and playback layers do not depend on NewPipe types.

### Playback

`PlaybackService` owns ExoPlayer and MediaSession. `PlaybackConnection` attaches a MediaController, forwards a resolved stream as a MediaItem, and exposes a small playback snapshot to the Activity. Media3's progressive, DASH, HLS, and SmoothStreaming modules are included so the source format can be handled by the corresponding media-source implementation.

Resolved stream URLs are short-lived and may expire. Persist the canonical source URL (for example, the YouTube video URL) in any future history or playlist feature; resolve a new stream URL immediately before playback. Never persist the signed stream URL as the durable content identity.

### UI and lifecycle

The current UI uses Android framework Views in `MainActivity`, not Compose. Search requests are scoped to a generation so a late response cannot replace newer results. A new search cancels the previous coroutine, and Activity destruction cancels its work and releases the controller connection. System-bar insets are applied to the root view because the app targets API 36.

The Activity owns only presentation and controller connection state. Playback itself is designed to continue in the service when the Activity stops; this still needs verification on physical devices.

## Social listening model — planned, not implemented

A room server should coordinate metadata and playback commands, not relay raw audio by default. The room state should eventually include:

- room identifier and participant membership
- current canonical content identifier and queue revision
- host/controller identity
- playing/paused state and authoritative position timestamp
- playback speed and a monotonically increasing event sequence

Clients should resolve and play media locally, then correct drift against the room clock. The server must handle late joins, host departure, reconnects, stale commands, and content that cannot be resolved on one participant's device.

No account system, room protocol, chat, WebSocket client, or backend is present yet. Choose the backend only after the room protocol, privacy model, and expected scale are specified.

## Verification gates

1. CI resolves dependencies, runs unit tests and Android lint, and assembles a debug APK.
2. The deterministic downloader and stream-selection tests pass without contacting YouTube.
3. A live extractor smoke test can search and resolve a public test item when explicitly enabled.
4. A physical Android device can play a resolved stream in the foreground through MediaController.
5. Playback continues after app switching and screen lock; notification, lock-screen, Bluetooth, audio-focus, noisy-route, seek, and service-recreation behaviours are checked.
6. Only after those gates should the project add persistent queues, playlists, local library, accounts, and social rooms.

A successful CI build does not prove that YouTube extraction or playback works on every device or network.
