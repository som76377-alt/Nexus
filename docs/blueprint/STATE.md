# STATE

Current: Phase 0, Task 0.1 — audit and stack confirmation
Working branch: `redesign/nexus-blueprint-v1`
Base branch: `integration/room-v2`
Base commit: `ea2c51e5fe5bc995063d752d9bf400ce0442ad3e`
Rule: check a task only after its approved change has been merged by the owner and its gates are satisfied. A PR or local/CI result alone does not mean merged.

## Phase 0 — Audit
- [ ] 0.1 Audit repo and write STACK.md
- [ ] 0.2 Create tracking files
- [ ] 0.3 Reconcile AGENTS.md with roadmap; log conflicts
- [ ] 0.4 Confirm bottom-bar bug cause and record it in STACK.md

## Phase 1 — Shell
- [ ] 1.1 Single root navigation/content host, fixed bottom bar, tab state preservation
- [ ] 1.2 Four tabs: Home, Search, Rooms, You; remove Library tab
- [ ] 1.3 Bottom-bar bounds equality test across tabs and Rooms keyboard state; LOOK CHECK

## Phase 2 — Foundations
- [ ] 2.1 Design tokens: colour roles, three radii, spacing scale, type scale
- [ ] 2.2 MaterialSurface flat placeholders, empty UiSoundPolicy, haptics helper, Reduce-effects setting
- [ ] 2.3 Visible-string audit and filler removal
- [ ] 2.4 You tab cleanup

## Phase 3 — Material proof slice
- [ ] 3.1 Acquire licensed real scans or record exact owner asset requests; register assets
- [ ] 3.2 Apply M1 canvas and Player body; M2 play knob; baked shadow/highlight layers and press state
- [ ] 3.3 Scroll parallax and Player-only tilt
- [ ] 3.4 Contrast/performance check; LOOK CHECK for Player and bottom bar

## Phase 4 — Library and Home
- [ ] 4.1 Local persistence for Saved, History, Playlists and Watch later
- [ ] 4.2 Home library shortcuts and destination screens
- [ ] 4.3 Three-dot menu and add-to-playlist flow

## Phase 5 — Recommendations
- [ ] 5.1 Genre-bucket config, seeded generator, dedupe store and cache
- [ ] 5.2 Home recommendation UI and whole-batch Reroll
- [ ] 5.3 Rounded-square tiles and sharp thumbnail fallback
- [ ] 5.4 Diversity unit tests and LOOK CHECK

## Phase 6 — Search
- [ ] 6.1 Dedicated Search screen and configurable genre/mood presets
- [ ] 6.2 Results through the existing search/provider interface

## Phase 7 — Blend
- [ ] 7.1 Local recency-weighted taste profile
- [ ] 7.2 Pair-scoped sharing table and consent flow
- [ ] 7.3 Blend generation and local playlist
- [ ] 7.4 Home shortcut visibility only when partner is connected

## Phase 8 — Material rollout
- [ ] 8.1 Apply material system to mini-player and remaining hero surfaces only
- [ ] 8.2 Contrast and anti-AI checklist sweep across all screens

## Phase 9 — Feel
- [ ] 9.1 Haptics on selected actions
- [ ] 9.2 Automatic cache policy in a separately flagged, permitted change

## Phase 10 — Performance and release readiness
- [ ] 10.1 Debug JankStats and DEVICE_TEST.md
- [ ] 10.2 Fix measured performance issues
- [ ] 10.3 Remove dead code and unused assets

## Phase 11 — Optional spike
- [ ] 11.1 Only if the owner reports baked lighting looks dead: compare a Player-only, feature-flagged shader against the baked version

Last session: 2026-11-15 — created the canonical roadmap on the redesign branch and audited the real Android Views stack, local persistence, navigation ownership and CI baseline. No app behavior has been changed yet.
