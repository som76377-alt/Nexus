# STATE

Current: implementation validation on redesign/nexus-blueprint-v1; no task is marked merged.
Base: integration/room-v2 at ea2c51e5fe5bc995063d752d9bf400ce0442ad3e.
Working branch: redesign/nexus-blueprint-v1.

**Status rule:** The branch contains a coordinated implementation pass, not an owner-merged completion. Original task checkboxes remain unchecked until the PR is merged by the owner and all required visual/device gates are satisfied. CI and device evidence are not yet available for this branch.

## Implementation map

| Blueprint area | Branch implementation | Remaining gate / deviation |
|---|---|---|
| Phase 0 audit | STACK, STATE, DECISIONS, NEEDS_USER and ASSET_REGISTER added; real Views stack and Rooms keyboard cause documented | Reconcile full roadmap against repo policy during review |
| Phase 1 shell | Home/Search/Rooms/You tabs; Search separated from Home; Home/Search scroll positions retained; Rooms bar remains visible after joining | Rooms remains a separate Activity; one root host and identical-bounds test not complete |
| Phase 2 foundations | Dark matte token palette, 3 radius roles, MaterialSurface placeholder, silent sound hook, haptics, Reduce effects, simplified You | Full string audit and anti-AI sweep pending CI/visual review |
| Phase 3 material proof | Shared canvas applied to Home/Rooms/Player/nav/mini-player; Player metal play control; tilt lifecycle-gated; infinite vinyl loop removed | No scanned material or baked highlight/shadow assets; parallax and performance measurements pending |
| Phase 4 library | Saved, History, Watch later, named playlists; Home shortcuts; list screens; track action menus | Still SharedPreferences, not Room; DB migration/tests incomplete; no queue actions exist in current UI API |
| Phase 5 recommendations | JSON-driven Home browser exposes all genre/language/film/long-form categories locally; category taps use the existing Search screen; seeded 60/40 mixer, 6-hour cache and 300-item shown history; all selected starter shelves fetch in a bounded 3-request batch; Reroll excludes visible tracks | Cache remains SharedPreferences rather than Room; recommendation tests deferred because no S1–S8 SPEC requirement applies; thumbnail fallback, exact-head CI and device proof pending |
| Phase 6 search | Separate Search screen with configurable genre/mood presets and existing catalogue interface | CI validation pending |
| Phase 7 Blend | Local 30-day half-life top-100 taste aggregation; deterministic 50-track generator; profile-only repository seam | Blend tests deferred because no S1–S8 SPEC requirement applies; pair table, consent, partner profile download and Home visibility require approved Supabase migration/RLS and real partner identity |
| Phase 8 material rollout | Shared material token system on canvas, Player, mini-player and nav | Scanned assets, baked lighting, contrast audit and full anti-AI gate pending |
| Phase 9 feel | Haptics on playback and save/seek actions | Automatic audio cache policy skipped as separate playback-adjacent task under current no-playback-scope rules |
| Phase 10 readiness | Debug-only JankStats wired to MainActivity and Rooms; DEVICE_TEST.md added | Weak-device measurements and owner run pending; JankStats output is not yet CI/device-measured |
| Phase 11 spike | Not started | Conditional on owner saying baked lighting looks dead; no shader work started |

## Original tasks (merge-gated)

### Phase 0 — Audit
- [ ] 0.1 Audit repo and write STACK.md
- [ ] 0.2 Create tracking files
- [ ] 0.3 Reconcile AGENTS.md with roadmap and log conflicts
- [ ] 0.4 Confirm bottom-bar cause and record it in STACK.md

### Phase 1 — Shell
- [ ] 1.1 Single root navigation/content host, fixed bottom bar and tab state preservation
- [ ] 1.2 Four tabs Home, Search, Rooms, You; remove Library tab
- [ ] 1.3 Bottom-bar bounds equality test across tabs and Rooms keyboard state; LOOK CHECK

### Phase 2 — Foundations
- [ ] 2.1 Design tokens
- [ ] 2.2 MaterialSurface, silent UiSoundPolicy, haptics and Reduce-effects
- [ ] 2.3 String audit
- [ ] 2.4 You tab cleanup

### Phase 3 — Material proof
- [ ] 3.1 Acquire/register licensed scans or owner asset requests
- [ ] 3.2 Apply M1/M2 and baked shadow/highlight/press layers
- [ ] 3.3 Parallax and Player-only tilt
- [ ] 3.4 Contrast/performance check and LOOK CHECK

### Phase 4 — Library/Home
- [ ] 4.1 Room tables for Saved, History, Playlists and Watch later
- [ ] 4.2 Home shortcuts and destination screens
- [ ] 4.3 Three-dot menu and playlist flow

### Phase 5 — Recommendations
- [ ] 5.1 Config, seeded generator, dedupe and cache
- [ ] 5.2 Home UI and Reroll
- [ ] 5.3 Square tiles and thumbnail fallback
- [ ] 5.4 Diversity tests and LOOK CHECK

### Phase 6 — Search
- [ ] 6.1 Dedicated Search and configurable presets
- [ ] 6.2 Results through existing search interface

### Phase 7 — Blend
- [ ] 7.1 Taste profile
- [ ] 7.2 Pair-scoped sharing table and consent
- [ ] 7.3 Blend generation and playlist
- [ ] 7.4 Home shortcut visibility when partner is connected

### Phase 8 — Material rollout
- [ ] 8.1 Mini-player and remaining hero surfaces
- [ ] 8.2 Contrast and anti-AI checklist sweep

### Phase 9 — Feel
- [ ] 9.1 Haptics
- [ ] 9.2 Automatic cache in a separately flagged permitted change

### Phase 10 — Performance/release readiness
- [ ] 10.1 Debug JankStats and DEVICE_TEST.md
- [ ] 10.2 Fix measured performance issues
- [ ] 10.3 Remove dead code and unused assets

### Phase 11 — Optional spike
- [ ] 11.1 Conditional Player shader experiment only after owner feedback

Last session: 2026-11-15 — implemented the first coordinated UI/library/recommendation/Blend-domain pass on the redesign branch. CI, device appearance, performance and migration/asset gates remain unverified or blocked.
- 2026-11-15 performance pass: Home now paints cached discovery shelves before network refresh, fetches one starter bucket per shelf before enriching the remaining buckets, and pre-resolves up to four visible tracks with two concurrent resolver slots. Playback taps consume a prefetched ResolvedAudio when available; uncached selections still use the existing validated resolver path.


2026-11-15 Home discovery/performance correction (pending CI and owner review): all configured category names now render from genre-buckets.json before any network call; category selection routes through the existing SearchPanel; the first recommendation batch requests every selected shelf with a maximum of three concurrent searches; catalogue construction, cache/history/library reads, search, uncached track resolution, and legacy WorkManager cleanup are moved off the UI thread; Search and Now Playing overlays are built only when opened; the redundant official-release marker filter no longer excludes ordinary category results; every displayed shelf item enters the 300-item exclusion history; both visible Reroll controls use the reroll path; recommendation rows use a one-shot 140 ms arrival transition, automatically omitted on Reduce effects/low-RAM/power-save. Debug timing records activity content-ready, first recommendation batch, and uncached track resolution. No provider, extractor, stream resolver implementation, PlaybackService, Media3 session, room transport or SQL code changed. Actual startup/play latency and visual appearance remain UNVERIFIED until physical-device measurement.
