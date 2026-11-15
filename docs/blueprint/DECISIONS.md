# DECISIONS

2026-11-15 | Root roadmap.md is the canonical owner-provided redesign plan; docs blueprint path points to it | The owner explicitly requested a repo-root roadmap file | Replace the pointer only if the owner asks for a second maintained copy.

2026-11-15 | Adapt Compose/Scaffold language to the repository's actual Android Views implementation | MainActivity and the UI package build native Views; introducing Compose solely to match terminology would expand the stack and risk working playback | Revisit only if the owner explicitly requests a later Compose migration.

2026-11-15 | Keep catalogue/provider, PlaybackService/Media3 and room transport behavior unchanged | Blueprint treats them as black boxes and AGENTS.md requires strict scope control | Any required adapter must stay at the UI/domain boundary and cannot change provider, playback or transport behavior.

2026-11-15 | Rooms remains a separate Activity for this implementation pass | ListeningRoomActivity owns a service-observing room UI and active-room restoration; converting it into a single-Activity destination would be a larger navigation/session-lifecycle refactor with regression risk | A future dedicated shell task can move the Room View into MainActivity after an explicit lifecycle design and tests; current bar is kept in the same bottom position and is no longer hidden after joining.

2026-11-15 | Use a flat charcoal placeholder material until real scans are licence-verified and available | No suitable downloaded image asset is committed; owner prohibited generated/procedural textures | Replace token backgrounds only with photographed/scanned CC0 material and register the exact source/licence.

2026-11-15 | The only verified current collection persistence is SharedPreferences, not Room | No Room dependency, schema or DAO existed; old Saved/History data must not be lost and synchronous MainActivity call sites should not block the UI on a new DB | Migrate to AndroidX Room in a distinct data-migration task with import-from-SharedPreferences and tests; current collections work but Phase 4.1 stays incomplete.

2026-11-15 | Recommendation cache uses SharedPreferences rather than Room | No Room layer exists and the app should not add synchronous DB access to scrolling UI | Move bucket-cache records to the Room database with 6-hour TTL and tests once Room migration is ready.

2026-11-15 | Blend profile contains only aggregated artist scores; raw track history is never shared | This reduces exposure and matches the owner blueprint | Revisit profile fields only with explicit owner consent and documented privacy review.

2026-11-15 | Blend backend sharing is not enabled | Pair-scoped Supabase table and RLS/migration must be added, but AGENTS.md prohibits migration edits without a proven migration-first path; MainActivity also has no confirmed partner-identity signal | Obtain approved migration-first workflow and define stable pair ID and one-time consent before enabling the shortcut.

2026-11-15 | Automatic audio caching changes are not included | Blueprint flags it as a separate player-adjacent PR and AGENTS.md forbids touching playback behavior within unrelated work | Revisit only as its own feature-flagged PR with owner approval and proof of zero transport/queue/sync changes.

2026-11-15 | Do not invent queue actions | Repository search found no existing UI/controller methods for add-to-queue, play-next or queue-next; blueprint says retain existing queue actions, not create a queue subsystem | Add queue actions only when an existing, verified queue API is exposed.

2026-11-15 | Thumbnail URLs remain those supplied by the existing catalogue interface | Provider/extractor/thumbnail URL resolution is out of scope and UI must treat catalogue as black box | A presentation-only image loader fallback can be added after current thumbnail host/format contracts are verified.

2026-11-15 | A finite Player spin replaces the old continuously repeating disc animation | Blueprint mandates zero permanent animation loops and reduced GPU use on weak phones | Revisit only after owner visual check, retaining state-change-only behavior.

2026-11-15 | Do not retain recommendation/Blend unit tests that cannot truthfully name an immutable SPEC S1–S8 requirement | AGENTS.md says all tests must cite an applicable SPEC ID and exercise observable behavior; none of S1–S8 specifies Home recommendations or Blend mix ordering. Their standalone tests were removed from this repo-policy branch rather than falsely tagged | Revisit only if the owner authorizes a repository test-policy exception or adds a separate applicable immutable product spec.
