# Provider API contract audit (E2)

**Status:** documentation-derived contract audit only. This is not a captured live-response fixture, and it does not prove that any configured public instance currently behaves like the published contract. No production parser changes are authorized by this document alone.

## Piped search contract

Source: TeamPiped OpenAPI, `/search` and `/nextpage/search`:
https://github.com/TeamPiped/OpenAPI/blob/main/swagger.yaml

The documented first-page request is:

- `GET /search?q=<query>&filter=<filter>`
- `filter` is required. Documented values include `all`, `videos`, `music_songs`, `music_videos`, `music_albums`, `music_playlists`, and `music_artists`.

The documented response is a **SearchPage object**, not a top-level JSON array:

- `items`: array of discriminated search items
- `nextpage`: nullable continuation token
- `suggestion`: nullable corrected/suggested query
- `corrected`: optional boolean

Search item discriminator values include:

- `stream`: video-like item; fields include `url`, `title`, `thumbnail`, `uploader`, `duration`, and `views`
- `channel`: channel item
- `playlist`: playlist item

The documented continuation request is:

- `GET /nextpage/search?nextpage=<token>&q=<query>&filter=<filter>`
- It returns another SearchPage object.

### Current Nexus mismatch to verify against live fixtures

`PipedStreamProviderAdapter.parseSearch` currently constructs `JSONArray(body)`, then accepts only items whose `type` is `stream`. If the configured Piped instance follows the published SearchPage contract, parsing the object as an array will fail before any items are mapped. The current adapter also has no way to preserve `nextpage`.

This is a strong contract mismatch, but E2 still requires real response captures from the actual instances Nexus uses before changing production parsing or pagination.

## Invidious search contract

Source: Invidious API documentation:
https://docs.invidious.io/api/

The documented request is:

- `GET /api/v1/search?q=<query>`
- Optional `page` is an integer page number.
- Optional `type` can restrict result types, including `video`, `playlist`, `channel`, and `all`.

The documented response is a **top-level JSON array** of mixed result objects. Common discriminator values include `video`, `playlist`, `channel`, and `hashtag`.

Video items include `videoId`, `title`, `author`, `videoThumbnails`, `lengthSeconds`, and `viewCount`. Non-video results have different shapes and must not be treated as playable tracks.

The current Nexus parser's top-level array assumption is consistent with this documented envelope. It filters to entries with a valid 11-character `videoId`, which avoids mapping playlist/channel objects as playable tracks. Live fixtures are still needed to validate actual configured instances and error behavior.

## Fixture capture requirements

Do not create synthetic JSON and label it as a provider fixture. Each real capture must include:

1. Provider name and configured instance host.
2. Capture timestamp in UTC.
3. Request path and query parameters (use a harmless fixed query such as `ambient music`; redact secrets if any are present).
4. HTTP status and response Content-Type.
5. Exact response body, with only credentials or personal data redacted. Preserve field names, nulls, nesting, discriminator values, and pagination tokens' presence/absence.
6. Whether the response came from a first-page or continuation request.
7. A SHA-256 digest of the unredacted body if the capture process can calculate it without retaining sensitive data.

Capture success, empty-result, rate-limit, client-error, server-error, and malformed-response cases separately when available. Do not hammer instances to force errors.

## Cross-provider ground-truth capture

For a small fixed set of ordinary search terms, capture first-page results from NewPipe, Piped, and Invidious close together in time. Compare normalized YouTube video IDs, not titles or result ordering. Keep each provider's raw response separate from the normalized comparison. Do not assume identical rankings or identical result counts.

## Scope boundary

- This document changes no runtime behavior.
- Do not add invented fixture payloads.
- Do not change `PipedStreamProviderAdapter` or `InvidiousStreamProviderAdapter` until real fixtures support the parser change.
- Do not claim cross-provider equivalence from API documentation alone.
- E1 phone measurements remain separate; they cannot be inferred from API schemas.
