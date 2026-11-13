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
./gradlew --no-daemon :app:testDebugUnitTest
```

An opt-in live smoke test searches public YouTube Music results and resolves one audio stream:

```bash
./gradlew --no-daemon -Pnexus.liveExtractorTests=true :app:testDebugUnitTest
```

The live test is intentionally skipped in normal CI because upstream availability and extraction behaviour can change independently of this repository.

## Current boundaries

- This milestone handles first-page search and audio stream resolution only; pagination, channel/playlist browsing, caching and UI integration are separate work.
- Public content is the intended test surface. Age-gated, account-only or otherwise unavailable content can fail extraction.
- The NewPipe Extractor is GPL-3.0-or-later. Review licence obligations before distributing a derivative application or binary.
- The extractor uses unofficial service-facing interfaces; upstream changes can require dependency updates. Keep this module isolated from playback and UI code.

## Provider Lab and provider health diagnostics (J3)

The debug-only Provider Lab can exercise NewPipe, Piped, and Invidious through the
existing provider resolver chain. It includes forced failure classes for bot checks,
rate limits, extractor faults, network errors, unavailable content, and forbidden
streams. A timed Media3 data-source fault can simulate a stream becoming forbidden
after a configured number of seconds.

The copied diagnostic report is limited to 40 lines and records app version/flavor,
network transport and IP-family availability, persistent cache size, local provider
configuration version/order, and provider success, failure, error, and cooldown
state. Provider chaos injection is implemented in the debug source set; the release
source set supplies a no-op factory. CI compiles release Kotlin and checks that the
debug chaos implementation and timed-403 classes are absent from release output.

CI uses deterministic unit/build checks only; it does not contact live extractor
services. A successful Provider Lab cell's audible playback, forced mid-stream
recovery, real IPv4/IPv6 behavior, and both-phone results remain UNVERIFIED until
timestamped reports are collected from the physical devices.

## Remote provider configuration and extractor maintenance (J4)

The app reads the data-only `config/providers.json` file from the repository's
main branch at startup and refreshes it every six hours while the process is alive.
Requests use ETag/If-None-Match. A valid response is stored as the last-good config;
a 304, offline state, HTTP error, oversized body, or invalid document keeps the
previous last-good config. A first install with no last-good value uses the bundled
`app/src/main/assets/providers.json` defaults.

The schema allows a positive integer `version` and one to three ordered provider
records with an allowlisted `id` (`newpipe`, `piped`, `invidious`) and boolean
`enabled`. Unknown object keys are ignored for forward compatibility. Unknown
provider IDs, duplicates, malformed fields, and a config with no enabled provider
reject the whole document. The config cannot contain code, executable URLs,
scripts, or arbitrary endpoint overrides.

`.github/workflows/newpipe-extractor-update.yml` checks the latest upstream
published release weekly and on manual dispatch. If the catalog version differs,
it opens/updates a dependency PR. It does not merge the PR and does not run live
YouTube tests; the repository's normal CI and human review remain required.

J4 unit tests use MockWebServer and deterministic JSON. They do not contact GitHub,
NewPipe, YouTube, Piped, or Invidious. The six-hour refresh cadence is process
lifetime scoped; a new app launch always performs a fresh conditional request.
