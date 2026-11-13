# Remote provider configuration and extractor maintenance (J4)

## Remote provider configuration

The app reads the data-only `config/providers.json` file from this repository at
startup and refreshes it every six hours while the process is alive. Requests use
ETag/If-None-Match. A valid response is stored as the last-good config; a 304,
offline state, HTTP error, oversized body, or invalid document keeps the previous
last-good config. A first install with no last-good value uses the bundled
`app/src/main/assets/providers.json` defaults.

The schema allows a positive integer `version` and one to three ordered provider
records with an allowlisted `id` (`newpipe`, `piped`, `invidious`) and boolean
`enabled`. Unknown object keys are ignored for forward compatibility. Unknown
provider IDs, duplicates, malformed fields, and a config with no enabled provider
reject the whole document. The config cannot contain code, executable URLs,
scripts, or arbitrary endpoint overrides.

## NewPipe Extractor update proposals

`.github/workflows/newpipe-extractor-update.yml` checks the latest upstream
published release weekly and on manual dispatch. If the catalog version differs,
it opens/updates a dependency PR. It does not merge the PR and does not run live
YouTube tests; the repository's normal CI and human review remain required.

## Verification boundary

Unit tests use MockWebServer and deterministic JSON. They do not contact GitHub,
NewPipe, YouTube, Piped, or Invidious. The six-hour refresh cadence is process
lifetime scoped; a new app launch always performs a fresh conditional request.
