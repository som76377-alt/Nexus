# Nexus visual foundation

**Status:** Phase A foundation specification  
**Identity:** The Art of Listening  
**Scope:** Design tokens, typography roles, materials inventory, asset rules and UI-layer boundaries.

This document is the source of truth for the new Nexus visual direction. It supersedes the previous parchment/ink/mineral/copper-only treatment. It does not authorize a screen redesign by itself.

## Art direction

Nexus combines independent music-label culture, contemporary editorial design, printed matter and analog listening objects. The interface must be colourful with deliberate colour relationships, not a random collection of bright cards.

Principles:
- Colourful, never childish.
- Experimental, never illegible.
- Tactile, never noisy.
- Rich, never cluttered.
- Album artwork is content, not decoration.
- Physical-looking assets are static images; only genuinely moving objects are animated.
- Functional UI remains readable and accessible.

## Foundation palette

| Token | Hex | Role |
|---|---|---|
| Archive Ivory | #F1E8D6 | Main light background |
| Carbon Ink | #26211E | Primary text / dark surfaces |
| Deep Vinyl Green | #205247 | Brand anchor |
| Sleeve White | #F8F2E6 | Content surface |

## Expressive palette

| Token | Hex | Intended associations |
|---|---|---|
| Oxblood | #793B48 | Ghazal, poetry, intimate collections |
| Cobalt | #405B91 | Classical, archival, contemplative |
| Terracotta | #C25D40 | Warmth, indie, energetic accents |
| Ochre | #D3A44D | Vintage print, highlights |
| Jade | #66836D | Organic, classical, calm |
| Dusty Rose | #B87879 | Romantic / soft editorial |
| Plum | #62445E | Alternative, personal |
| Petrol | #397C7B | Contemporary, atmospheric |
| Saffron | #E0B65D | Selective high-energy accent |

These are semantic anchors, not permission to apply every colour to every component. Genre palettes are curated combinations, never arbitrary per-card colour assignment. Album-derived accents must be constrained by contrast and palette rules.

## Dark appearance

| Token | Hex |
|---|---|
| Background | #191917 |
| Surface | #252420 |
| Elevated | #302D28 |
| Primary text | #F0E6D5 |
| Secondary text | #B9AE9D |
| Green | #72988A |
| Copper | #CF8057 |
| Plum | #A77B92 |
| Gold | #D0AF69 |

Dark appearance is independently art-directed; it is not a mechanical inversion of light mode.

## Typography system

| Semantic role | Selected family | Use |
|---|---|---|
| Display | Fraunces | Major editorial headings and selected feature titles |
| Experimental | Syne | Genre/collection display lettering and graphic compositions |
| Editorial | Instrument Serif | Song/artist/editorial accents |
| Interface | DM Sans | Controls, descriptions, navigation and functional labels |
| Technical | Space Mono | Durations, timestamps, track numbers and room codes |

Do not use all five families on one screen. Display and experimental type is reserved for hierarchy and decorative compositions. Functional information must remain legible.

Indian scripts must use suitable script-specific families, including Noto Serif Devanagari, Noto Sans Devanagari and Noto Nastaliq Urdu where applicable. Never assume Latin font coverage.

**Asset status:** Fraunces, Syne, Instrument Serif, DM Sans and Space Mono are bundled as Android font resources from the official Google Fonts repository under SIL Open Font License 1.1. The corresponding licence texts and source mapping are stored in `docs/licenses/fonts/`. Devanagari and Nastaliq families remain a separate script-coverage follow-up; the current Latin families must not be assumed to cover those scripts.

## Material and texture inventory

| Material | Intended use | Rules |
|---|---|---|
| Handmade cotton paper | Selected Home editorial backgrounds | Real photographed fibres; very low contrast |
| Fine cream stock | Light content surfaces | Keep song rows mostly clean |
| Aged printed stock | Featured editorial collections | Use selectively |
| Linen / book cloth | Library accents | Never behind dense text |
| Burgundy velvet | Occasional Ghazal feature artwork | Not a global screen background |
| Matte charcoal paper | Now Playing | Restrained, dark and low-detail |
| Record-sleeve cardboard | Album/playlist editorial compositions | Preserve artwork readability |
| Offset-print ink | Poster/genre artwork | Asset-level treatment, not global overlay |
| Metallic ink | Rare special-edition details | Never fake with gradients |
| Woven fabric | Room accents | Chat surface remains high contrast |

### Texture asset requirements

- Source from genuine photographed material, not procedural noise.
- Prefer seamless source images only when the material itself naturally tiles.
- Keep a high-quality master and generate density-appropriate compressed variants.
- Use WebP/AVIF where the image pipeline supports them, with a compatible fallback.
- Never decode a huge master bitmap at screen size.
- Cache assets and avoid per-frame rendering.
- Do not texture every card, button or screen.
- No repeated identical texture across every destination.
- Record, tonearm and album artwork are separate visual assets, not procedural approximations.

## Layout and component tokens

| Token | Value |
|---|---:|
| Screen horizontal margin | 22dp |
| Screen vertical margin | 24dp |
| Section gap | 30dp |
| Compact gap | 8dp |
| Functional control height | 48dp |
| Navigation height | 68dp |
| Mini-player artwork | 48dp |
| Minimum touch target | 48dp |
| Artwork corner | 6dp |
| Control corner | 10dp |
| Panel corner | 18dp |

Use asymmetry and editorial composition where appropriate. These tokens are defaults, not a requirement that every screen use identical cards.

## Asset directory contract

Additional assets belong in:
- `app/src/main/res/font/` — bundled licensed font binaries (Fraunces, Syne, Instrument Serif, DM Sans, Space Mono).
- `app/src/main/res/drawable-nodpi/` — photographic textures, vinyl and tonearm masters/variants.
- `app/src/main/res/drawable/` — scalable vector artwork and density-independent drawables.
- `app/src/main/res/mipmap-*/` — launcher artwork only.

Every third-party asset must have a recorded source, licence, attribution requirement and intended use. No unlicensed font or image may be committed.

## UI architecture boundaries

The design-system layer may own:
- Colour, typography, spacing and shape tokens.
- Static drawable construction.
- Reusable presentation-only components.

It must not own:
- Playback state or MediaSession behaviour.
- Room synchronization, transport or Supabase state.
- Catalogue search or stream resolution.
- Persistence or library repository behaviour.
- Updater scheduling or installation logic.

Existing functional pathways remain authoritative. Visual work must not fork playback or room commands.

## Phase A exit criteria

- One central semantic palette and typography-role API exists.
- Legacy presentation aliases resolve to the new identity rather than the previous dark neon palette.
- Licensed font binaries are bundled with their SIL OFL notices.
- No unlicensed or speculative binary assets are added.
- No protected playback, room, SPEC or SQL files are changed.
- Android CI (lint, unit tests, debug APK and release guard) passes on the exact proposed commit.
- No claim of visual/device verification without a real device review.
