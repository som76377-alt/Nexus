# NewPipe Extractor integration

## Implemented

- `OkHttpDownloader` adapts NewPipe's `Downloader.execute(Request)` contract to OkHttp.
- HTTP method, request body, extractor-supplied headers, response headers, status, body and final redirected URL are preserved.
- HTTP 429 is surfaced as NewPipe's `ReCaptchaException`; the app does not spin in an automatic retry loop.
- `YoutubeCatalogRepository.search` supports all-results, music-song and video search scopes.
- Search items are mapped into app-owned `CatalogTrack` models.
- `resolveAudio` extracts stream metadata and selects the highest-bitrate directly addressable audio stream; progressive HTTP is only a tie-breaker when bitrates match.
- The selector accepts only well-formed HTTPS URLs with a host, and skips manifest bodies. Cleartext HTTP playback is disabled in the Android manifest.
- Extractor calls run on `Dispatchers.IO`.

## Important stream URL rule

Resolved media URLs may be signed and expire. Persist the canonical source/video URL in playlists and history, not `ResolvedAudio.audioUrl`. Resolve again immediately before playback.

## Tests

Ordinary local unit tests use MockWebServer and do not contact YouTube:

```bash
gradle --no-daemon :app:testDebugUnitTest
```

An opt-in live smoke test searches public YouTube Music results and resolves one audio stream:

```bash
gradle --no-daemon -Pnexus.liveExtractorTests=true :app:testDebugUnitTest
```

The live test is intentionally skipped in normal CI because upstream availability and extraction behaviour can change independently of this repository.

## Current boundaries

- This milestone handles first-page search and audio stream resolution only; pagination, channel/playlist browsing, caching and UI integration are separate work.
- Public content is the intended test surface. Age-gated, account-only or otherwise unavailable content can fail extraction.
- The NewPipe Extractor is GPL-3.0-or-later. Review licence obligations before distributing a derivative application or binary.
- The extractor uses unofficial service-facing interfaces; upstream changes can require dependency updates. Keep this module isolated from playback and UI code.
