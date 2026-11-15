# DECISIONS

2026-11-15 | Root `roadmap.md` is the canonical owner-provided redesign plan; `docs/blueprint/NEXUS_BLUEPRINT.md` points to it | The owner explicitly requested the file be placed in the repository as `roadmap.md`; this avoids maintaining two diverging copies | If the owner later requests a second full copy, replace the pointer with an identical copy of root roadmap.md.

2026-11-15 | Adapt Compose/Scaffold language to the repository's actual Android Views implementation | `MainActivity.kt` and the UI package build native Views programmatically; introducing Compose solely to satisfy terminology would expand the stack and add risk | Revisit only if a future approved migration explicitly calls for Compose.

2026-11-15 | Keep catalogue/provider, PlaybackService/Media3 and room transport behavior unchanged during this redesign | The roadmap explicitly treats them as black boxes; AGENTS.md also requires local-first playback and strict scope control | Any required adapter must be presentation-only or a narrowly scoped interface with no provider/playback/transport changes.

2026-11-15 | The Rooms navigation problem is structurally caused by two Activity-owned navigation bars plus adjustResize in ListeningRoomActivity | The Room screen is a separate Activity with its own bottom bar; the soft keyboard resizes the root vertical layout. Exact screen bounds still require a UI/device test | A single-host shell may replace this structure only after a full review confirms room session ownership and no playback/transport code changes.

2026-11-15 | Current Saved/History persistence is SharedPreferences, not AndroidX Room | LocalLibraryStore persists JSON track metadata and no playlist entities; the roadmap's Room database work is genuinely new, not already complete | Add a database only through an explicit migration plan and compatible, tested schema; do not misreport the current store as Room.

2026-11-15 | Supabase Blend table changes are blocked pending an approved migration-first path | AGENTS.md forbids modifying SQL migrations without proving the migration-first path; the roadmap calls for a new pair-keyed table | Keep the Blend UI/domain behind a stub seam and add the exact migration need to NEEDS_USER.md; do not modify SQL yet.

2026-11-15 | Automatic audio caching changes are blocked from this visual redesign branch | The roadmap flags it as a separate, player-adjacent PR, while AGENTS.md requires scope control and playback-layer isolation | Leave existing audio cache code untouched; revisit only as a distinct permitted task with tests and an explicit feature flag.
