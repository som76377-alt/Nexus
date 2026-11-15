# NEXUS BLUEPRINT v1

Authoritative build plan for the Nexus redesign and home/search/library rework. Written by the lead reviewer for the implementing agent. Read this whole document at the start of every session.

## 0. Start here (read first, every session)

You are the implementing agent. The owner will mostly say one word: **next**. Your job is to make that sufficient.

**First action of every session, before anything else:**
1. Read, in this order: `AGENTS.md`, `docs/blueprint/NEXUS_BLUEPRINT.md` (this file), `docs/blueprint/STATE.md`, `docs/blueprint/DECISIONS.md`, `docs/blueprint/NEEDS_USER.md`.
2. Reply with exactly one line: `Resuming at Phase X, Task Y: <title>.`
3. Do that task. Nothing else.

**What "next" means:** complete exactly the next unchecked task in `STATE.md`, update `STATE.md` and `DECISIONS.md` in the same pull request, then stop and report in 3 sentences or fewer. Never jump back to an earlier phase, never jump ahead, never start a different workstream.

**Precedence of rules**
- Safety rules in `AGENTS.md` always win where they are stricter: CI is the compiler (no local Gradle), small PRs, no merges, no changes to main, no releases, no signing changes, anything unverified is labelled UNVERIFIED.
- This blueprint wins on priorities, scope and design.
- If `AGENTS.md` blocks a task in this blueprint, do not stop. Log the conflict in `DECISIONS.md`, do the closest allowed version, add an entry to `NEEDS_USER.md`, and continue with the next unblocked task.

## 1. Operating protocol: how to work without hand-holding

**Decide, do not ask.** For every choice that is reversible (naming, layout details, which of two reasonable approaches, which texture variant), apply the decision rules in this document, pick, and record one line in `DECISIONS.md` (date, decision, reason, how to reverse). Do not present options and wait.

**Ask the owner only for these four things**, via `NEEDS_USER.md`, and keep working on other tasks meanwhile:
1. Things only a human can supply: real photographs or scans you cannot download, recorded sounds, device testing results, credentials.
2. Irreversible or destructive actions (deleting user data, changing signing, releases).
3. Conflicts with `AGENTS.md` safety rules.
4. A visual LOOK CHECK at the end of visual phases (see Section 7). One line: which build to install, which two screens to open, what to reply.

**Drift prevention (the failure that already happened).** The previous sessions lost the plan, went back to old APK work and started provider work when the owner asked for visual work. Therefore:
- `STATE.md` is the single source of truth, not chat memory. If your memory and `STATE.md` disagree, `STATE.md` wins.
- Provider, extractor, NewPipe, stream resolution and room-sync work is **out of scope** for this blueprint. Do not start it, do not "fix" it in passing.
- If a task needs data from a provider, use the existing search/provider interface as a black box. If it cannot supply what you need, add a thin adapter interface, stub it, log it, and continue.
- Do not refactor code that no task mentions.

**Never claim what you cannot verify.** You cannot see the phone screen. State "built and tests pass in CI" or "UNVERIFIED on device". Never write "looks great".

## 2. Goal, and what was decided about contested ideas

**Goal:** Nexus should feel like a crafted object, not a themed Spotify clone: one coherent physical material world, restrained, fast on a weak phone, with no AI-template tells.

The design discussion produced contradictions. These decisions are final unless the owner overrides them.

| Question | Decision | Reason |
|---|---|---|
| Texture instead of colour on every card? | **No.** Textures only on a few hero surfaces. Content (lists, cards) sits flat and takes colour from album art. | Texture behind text and thumbnails hurts legibility, fights album art, and is the 2012 skeuomorphism trap. |
| Where do textures come from? | Real photographed or scanned materials only (CC0 libraries or the owner's own photos). No generated textures, no procedural noise. | Owner requirement. Authenticity. |
| Dynamic lighting? | **Baked layers plus cheap motion** (Section 4.2). No runtime shader by default. | Runtime shaders are heavier on weak phones and risk the "code-faked" look. |
| Fixed shadows look dead? | Shadows are baked, but highlight and shadow layers move with scroll and, on the Player only, with device tilt. | Gives life at near-zero cost. |
| Click sounds per material? | **Not in the first release.** Default is silence. A sound hook exists, ships empty. Only real recordings supplied by the owner, later. | A synthesized or generic sound on a glass screen feels fake, and sounds must never compete with music. |
| Haptics? | Yes, but only Android's standard haptic constants, on a few meaningful actions. | Real, free, respects system settings. |
| Colour palette? | Derived from the chosen material and album art. Do **not** default to beige/ivory/olive/bronze/cream. | Those are the current "AI premium" tell. |
| Cache button in You? | Removed. Caching is automatic and invisible. | Owner requirement. |
| Library tab? | Removed. Library shortcuts move to Home. | Owner requirement. |

## 3. Hard constraints

- Target the weaker phone, not the iQOO Z9s Pro 5G. The partner's phone has stability problems; if it runs smoothly there, it runs everywhere. Treat 3 to 4 GB RAM and a weak GPU as the design target.
- The visual layer must never be able to interrupt playback or room sync. No visual code may touch `PlaybackService`, Media3 session code, or room transport.
- Every decorative effect must be switchable off, and the app must be fully usable with all of them off.
- No permanent animation loops. Animation only in response to touch, scroll, tilt on the Player, or a state change.
- UI sounds never play while music is playing. Never request audio focus for a UI sound.
- Android stack assumptions (Kotlin, Jetpack Compose, Media3, Room, Supabase, Coil) are UNVERIFIED until Phase 0 confirms them. If the real stack differs, adapt the tasks and log it.

## 4. Design rules

### 4.1 Material system

- Maximum **two material families** in the whole app, defined once in one place:
  - **M1 Canvas material:** the app background and large surfaces. Default candidate: a dark, matte, low-contrast scanned material (fine leather grain or dark stone). Pick by testing text contrast, not taste. Text on it must reach contrast ratio 4.5 to 1 minimum.
  - **M2 Accent material:** metal, used on a **single** control, the main play/pause knob, and nowhere else.
- Leather stitch marks and similar tiny details may be small SVG assets on a few corners only (Player edge). They must be hand-simple, uniform, and not noticeable at a glance.
- All material use goes through one composable, `MaterialSurface(role, ...)`, driven by tokens. No screen may reference a texture file directly. Swapping a material later must be a token and asset change, nothing else.
- Until real assets exist, `MaterialSurface` renders a flat colour from the same tokens. The app must always build and look coherent with placeholders.

**Asset pipeline (Phase 3 and 8):**
1. Try to download CC0 scans from Poly Haven or ambientCG (leather, dark stone, brushed metal) with albedo and, if useful, ambient-occlusion maps. Check each licence on its page.
2. If your environment cannot download, write exact asset requests (site, material name, resolution, map type) in `NEEDS_USER.md`, and continue with placeholders.
3. Process: crop to tileable 512 px or a single 1080 px wide canvas, colour-match to one warm or cool direction, export WebP. Total asset budget: **8 MB or less** added to the APK. Decoded bitmaps in memory: **24 MB or less** total.
4. Log every asset in `docs/blueprint/ASSET_REGISTER.md`: source URL, creator, licence, original size, processed file, where used.
5. Never AI-generate, procedurally generate, or upscale-fake a texture.

### 4.2 Lighting that is not dead and not code-faked

Use only these techniques, on **at most three hero surfaces** (Player body, mini-player, bottom navigation bar):
- **Baked shadow and edge-highlight sprites**, authored in an image editor or taken from real photographs, as separate layers above the base material.
- **Parallax:** shift the highlight layer a few dp opposite to scroll, at about 15 percent of scroll speed, clamped.
- **Tilt (Player screen only):** sample the accelerometer at the slow UI rate while the Player is visible, translate the highlight layer by at most 6 dp. Unregister the sensor when the screen is not visible, when Reduce effects is on, or in battery saver.
- **Press state:** one pre-made pressed variant of the play knob (inset, shadow reduced), crossfaded in 90 ms.

Everything else is flat and static. No blur, no glow, no gradients invented in code to fake depth.

### 4.3 Anti-AI-tell rules (a gate on every UI pull request)

Include this checklist in every UI pull request description, with yes or no for each. Any "no" blocks the PR.

1. No card inside a card. Not every section is a rounded rectangle; some content sits directly on the canvas.
2. At most **three corner radii** in the app, defined as tokens by role (small control, media tile, large surface). Not one radius on everything.
3. No uppercase letter-spaced micro-labels. No subtitle under every heading.
4. No decorative divider lines, no decorative dots, no fake coordinates, serial numbers, edition numbers or version stamps.
5. No icon inside every card by default. An icon exists only to trigger an action.
6. One type family, at most two weights, a clear size scale of at most six sizes. Numeric text uses tabular figures.
7. Deliberate asymmetry and scale contrast: at least one element per screen is clearly larger or smaller than the rest by role, not everything on one grid with equal weight.
8. Empty space is left empty. Do not fill gaps with decoration.
9. No emoji in the interface.
10. Every string passes the copy rules below.

### 4.4 Copy rules

Every visible word must give the user information or an action.
- Allowed: titles of real sections, names of real things, real state ("Not connected"), real errors with a next step, button labels.
- Banned: slogans, quotes, taglines, "curated for you", "your personal universe", explanations of obvious controls, branding repeated per section, headings that only decorate.
- Phase 2 includes a **string audit**: list every user-facing string, delete or justify each, record the result in `DECISIONS.md`.

### 4.5 Sound and haptics

- No sound assets ship in the first pass. Create a `UiSoundPolicy` interface and an empty implementation so a future real recording can be dropped in.
- Never synthesize, generate or download generic sound packs.
- Later, if the owner supplies real recordings: off by default while music plays, separate switches for sound, haptics and motion, respect system silent mode.
- Haptics: use standard `HapticFeedbackConstants` (for example CONFIRM on play, CLOCK_TICK on seek steps). Not on every tap.

### 4.6 Performance budgets (acceptance targets, measured, not promised)

| Metric | Target |
|---|---|
| Scroll on Home and Search, weak phone | Janky frames under 3 percent |
| Frame time | About 16.7 ms at 60 Hz |
| Texture and image decode | Never on the main thread |
| Added APK size from assets | 8 MB or less |
| Decoded texture memory | 24 MB or less |
| Overdraw | At most 2 layers over any pixel in lists |
| Infinite animations | Zero |
| Playback and room sync | No change in behaviour |

Rules: decode each texture once and reuse; draw textures with an image brush or `drawBehind`, not stacked translucent layers; avoid full-screen alpha animations; list items are flat and cheap; effects turn off automatically on low-RAM devices and battery saver, and there is a manual **Reduce effects** switch in You.

## 5. Feature specifications

### 5.1 Navigation fix
Four tabs: **Home, Search, Rooms, You.** The bottom bar must stay in exactly the same place on every tab. Currently it rises on Rooms.
- Likely causes, to be confirmed in Phase 0 (UNVERIFIED): a second `Scaffold` or its own bottom bar inside Rooms; keyboard inset handling (`imePadding` or `adjustResize`) applied to the whole Rooms screen instead of only the chat composer; insets consumed twice.
- Fix pattern: one root `Scaffold` at activity level owns the bottom bar. Child screens never declare a bottom bar. Keyboard padding applies only to the chat input. Tab switches preserve each tab's scroll and state.
- Gate: a UI test asserting the bottom bar bounds are identical on all four tabs, including with the keyboard shown in Rooms.

### 5.2 Home
- Top: a library shortcut row with four items: **Saved, History, Playlists, Blend.** Compact, tappable, no explanatory text. Blend is shown only when a partner is connected.
- Below: Recommendations (5.3). Remove the existing fake quotes and decorative text.
- The Library tab no longer exists; the same content lives behind these shortcuts.
- Data: Saved, History and Playlists are local (Room). **Watch later** is a built-in playlist. All reachable from the three-dot menu on any track.

### 5.3 Recommendations that actually vary
Problem: the current list is nearly identical on every open. Fix with a deterministic, testable mix:
- Each batch has at least **20 tracks**, drawn from at least **5 distinct genre buckets**.
- Mix: about 60 percent taste-based (related to the user's top artists and recent plays), about 40 percent exploration from genre buckets chosen at random, including genres the user does not listen to.
- Genre buckets live in a config file, not hard-coded in UI.
- Every app open creates a new random seed. A **Reroll** button replaces the whole batch (not one or two tiles) with a new seed.
- Dedupe: keep the last **300 shown track ids** with expiry; a new batch excludes them. Reroll excludes the current batch too.
- Cost: fetch per bucket, cache results in Room with a 6 hour TTL, never block the UI (show static placeholders, no shimmer loops), cap concurrent requests.
- Test: a unit test where two different seeds give at most 30 percent overlap, and every batch meets the 5-bucket minimum.

### 5.4 Track tiles and thumbnails
- Tiles are **rounded squares** using the media-tile radius token. Square images are centre-cropped.
- Blur fix (UNVERIFIED until the code is read): thumbnails are probably a small variant (`default` or `mqdefault`) scaled up. Request an image at least display size times screen density in pixels. For YouTube-style thumbnails, try the highest variant that exists and fall back in order (`maxresdefault`, `sddefault`, `hqdefault`); handle 404 on a variant by falling back. If the provider returns square music art with a size parameter, request it at the needed pixel size.
- Remove any blur effect applied to thumbnails.
- Each tile has a **three-dot menu**: Add to playlist, Save, Watch later, and the existing queue actions.

### 5.5 Search tab
- A tab of its own. Search leaves Home entirely.
- Under the search bar: a horizontally scrolling row of **genre and mood presets** (for example Pop, Hip hop, Lo-fi, Rock, Bollywood, Punjabi, Classical, EDM, Focus, Workout). Preset list lives in config. Tapping one runs a search with a query template and shows results below.
- Use the existing search/provider interface as a black box. Federated multi-provider search is a separate project; do not build it here.

### 5.6 Blend
- A playlist built from both partners' tastes, like Spotify Blend.
- Each phone computes a compact **taste profile**: top 100 artists with recency-weighted scores (half-life about 30 days). Only this aggregate is shared, never raw history.
- Share through a **separate** Supabase table keyed by the pair, not through the room sync transport. First use shows a one-time confirmation to share the taste profile with the partner.
- Algorithm: artists in both lists first, then alternate between each person's favourites, then add related tracks for both; target 50 tracks; refresh daily or on demand. Stored as a local playlist named Blend.
- Hidden when no partner is connected.

### 5.7 You tab
- Remove the cache section and the Clear button.
- Keep only what has a purpose: partner and room status, Reduce effects, haptics switch, app version and the existing update control.
- No filler.

### 5.8 Automatic caching (playback-adjacent, flagged)
- Done silently. Policy: keep the current track and the next track; evict others; hard cap about **100 MB** with least-recently-used eviction; trim on app start.
- Strictly clearing on every song change makes skipping back slow; the capped LRU is the decision. Record this in `DECISIONS.md`.
- This touches the player layer. It goes in its own small pull request, behind a feature flag, with no change to transport, queue or sync logic. If `AGENTS.md` forbids it, log and skip.

## 6. Phases and tasks

Mark each task in `STATE.md` when merged by the owner. One task per "next". Visual phases end with a LOOK CHECK.

**Phase 0. Audit (no visual change)**
- 0.1 Read the repo. Confirm the real stack. Write `docs/blueprint/STACK.md` (modules, UI toolkit, navigation, image loader, DB, where the bottom bar and Rooms screen live). Mark unknowns UNVERIFIED.
- 0.2 Create `STATE.md`, `DECISIONS.md`, `NEEDS_USER.md`, `ASSET_REGISTER.md` from the templates in Section 9.
- 0.3 Reconcile `AGENTS.md` with this blueprint; log conflicts.
- 0.4 Find the bottom bar bug cause; write it in `STACK.md`.

**Phase 1. Shell**
- 1.1 Single root scaffold, fixed bottom bar, tab state preservation.
- 1.2 Remove the Library tab; four tabs Home, Search, Rooms, You.
- 1.3 Bounds-equality UI test for the bar. LOOK CHECK: bar steady on all tabs.

**Phase 2. Foundations**
- 2.1 Design tokens: colour roles, three radii, spacing scale, type scale.
- 2.2 `MaterialSurface` with flat placeholders, `UiSoundPolicy` empty hook, haptics helper, Reduce-effects setting.
- 2.3 String audit and removal of filler text everywhere.
- 2.4 You tab cleanup (5.7).

**Phase 3. Material proof slice (early visual win, small scope)**
- 3.1 Asset acquisition or `NEEDS_USER.md` requests; asset register.
- 3.2 Apply M1 to the app canvas and Player body, M2 to the play knob, baked shadow and highlight layers, press state.
- 3.3 Parallax on scroll and tilt on Player.
- 3.4 Contrast and performance check. LOOK CHECK: Player and bottom bar.
- Rule: if the look fails the check, adjust tokens and assets, do not add more effects.

**Phase 4. Library and Home layout**
- 4.1 Room tables: saved, history, playlists, watch later.
- 4.2 Library shortcut row on Home; screens for each list.
- 4.3 Three-dot menu and add-to-playlist flow.

**Phase 5. Recommendations**
- 5.1 Bucket config, seeded generator, dedupe store, cache.
- 5.2 Home recommendations UI and Reroll.
- 5.3 Rounded-square tiles and sharp thumbnails (5.4).
- 5.4 Diversity unit tests. LOOK CHECK: open the app three times, reroll twice, confirm the lists differ.

**Phase 6. Search tab**
- 6.1 Search screen and presets. 6.2 Results using existing provider interface.

**Phase 7. Blend**
- 7.1 Taste profile computation. 7.2 Pair table and sharing. 7.3 Blend generation and playlist. 7.4 Home shortcut visibility.

**Phase 8. Material rollout**
- 8.1 Apply the material system to the mini-player and remaining hero surfaces only (max three).
- 8.2 Final contrast and anti-AI checklist sweep across all screens.

**Phase 9. Feel**
- 9.1 Haptics on the agreed actions. 9.2 Auto cache (5.8) in its own flagged PR.

**Phase 10. Performance and release readiness**
- 10.1 JankStats logging in debug builds, a `DEVICE_TEST.md` checklist for the owner to run on both phones.
- 10.2 Fix what the measurements show. 10.3 Remove dead code and unused assets.

**Phase 11. Optional spike**
- 11.1 Only if the owner says the baked lighting looks dead: a feature-flagged shader lighting prototype using real scanned normal maps on the Player only, API 33 and above, falling back to the baked version. Compare on device; keep only if smoother and visibly better.

## 7. Verification and gates

Every pull request must have: CI build passing; unit tests for logic; no change outside the task; `STATE.md` and `DECISIONS.md` updated; anything not proven labelled UNVERIFIED.

**Visual gate (because you cannot see the screen):** CI screenshot tests or Compose previews for changed screens if the tooling exists (check in Phase 0). The real visual judgement is the owner's, through a LOOK CHECK of one line: `Install build <name>. Open <screen A> and <screen B>. Reply "next" if good, or one line saying what is wrong.`

**A phase is done only when:** its tasks are merged, CI is green, the LOOK CHECK (if any) is answered, and the Section 4.3 checklist is yes on every item for the changed screens.

## 8. Known risks and honest limits

- Quality ceiling: no process guarantees a 10 out of 10 design. This blueprint raises the floor and removes known AI tells, but the final polish depends on the real material assets and the owner's eye on device.
- Assets are the bottleneck. CC0 scans are real but a texture that looks wrong beside another is easy to pick; keep materials to two and judge on device.
- Baked lighting is an approximation, not physical lighting. That is a deliberate trade for speed and authenticity.
- Recommendations depend on what the provider can return. If the provider is broken, tasks 5.x stay testable with stubs, and real variety is UNVERIFIED until search works.
- Provider and playback fixes are a separate workstream. If playback is currently broken, record it in `NEEDS_USER.md` and keep building the UI against stubs.
- The stack assumptions in Section 3 are unverified until Phase 0.

## 9. Templates for the tracking files

**docs/blueprint/STATE.md**
```
# STATE
Current: Phase 0, Task 0.1
## Phase 0
- [ ] 0.1 Audit repo and write STACK.md
- [ ] 0.2 Create tracking files
(continue with every task from Section 6)
Last session: <date> <one line>
```

**docs/blueprint/DECISIONS.md**
```
# DECISIONS
<date> | <decision> | <reason> | <how to reverse>
```

**docs/blueprint/NEEDS_USER.md**
```
# NEEDS_USER
Open items only. Each: what, why, which task is waiting, what the agent will do meanwhile.
```

**docs/blueprint/ASSET_REGISTER.md**
```
# ASSET REGISTER
| file | source URL | creator | licence | original size | processed | used in |
```
