# Nexus engineering instructions

## Role and objective

Work in the existing Nexus Android music and synchronized-listening repository. The goal is a reliable, simple, local-first room playback system. Prefer proven, small changes over new architecture, new features, or speculative abstractions. Research the existing implementation and tests before changing code. Never claim behavior that was not verified.

## Non-negotiable rules

1. **Green-first verification.** Run the narrowest relevant tests while developing, then Android lint, unit tests, and debug APK assembly before declaring an Android change ready. Inspect the actual CI checks for the exact pushed commit. A green compile alone is not sufficient.
2. **Atomic work.** Use a separate task branch from the designated integration branch. Make one logical change per commit/push. Do not stack another change on top of a failing CI run. Keep commits focused; split unrelated work.
3. **CI failure discipline.** For each task, inspect the failing test or compiler output and fix the cause, not the assertion. Maximum three attempts per task. After three failed attempts, revert that task's changes, mark it BLOCKED in WORKLOG.md with the reason, and continue with independent tasks. T1 is the gate: if T1 is blocked after three attempts, stop the queue.
4. **No speculative state.** Do not add boolean flags to coordinate playback. Prefer one explicit phase/state machine owned by the controller and a small, clear interface. Remove obsolete state and callbacks when replacing them. Reduce complexity and line count where a task requires it.
5. **Tests derive from SPEC.** Tests must name the applicable SPEC requirement (S1–S8) and exercise observable behavior. Do not weaken, delete, or rewrite assertions merely to make CI green. Remove only proven exact duplicates or tests of deleted private implementation details when the task explicitly calls for it.
6. **SPEC ownership.** SPEC.md is owner-owned and immutable after its initial integration commit. Never edit SPEC.md in subsequent task branches or pull requests. CI must fail any later PR that changes SPEC.md. Tests and reviews cite SPEC.md line ranges; implementation changes must conform to it, not redefine it.
7. **Two-device evidence.** Do not claim real synchronization, lock-screen, interruption, latency, or device behavior without evidence from two physical devices. Mark such behavior UNVERIFIED when only code and CI have been checked.
8. **Local-first and scope control.** Local playback must never wait on room/network work. Do not expand scope, add features, modify SQL migrations without proving the required migration-first path, or create new architecture records. Do not alter unrelated app behavior.
9. **Honest reporting.** Report actual branch, commit, CI run, failures, test coverage, and line delta. No fake completion claims, no vague “fixed” statements, no defending a broken result. If a decision is genuinely ambiguous, record DECISION-NEEDED in WORKLOG.md and report it.
10. **Branch safety.** Never commit directly to main, never merge a task branch into main, and never delete branches. Task PRs merge only into integration/room-v2 after green CI. The final integration-to-main PR must remain a single draft PR and must not be merged.

## Room design constraints

- The controller is the source of truth for local user intent and room state transitions.
- Keep one shared timeline and a bounded new-track readiness barrier. A peer that is not ready must not block local playback.
- Only explicit user actions are shared. System interruptions are local-only and must not be echoed as room commands.
- Avoid duplicate publication paths. Debounce/coalesce burst intents and retry transient publication failures with bounded backoff.
- A disconnected/dead partner must not freeze the local player or leave the UI hanging.
- End-of-track behavior is stop-on-both, with no autoplay.

## Work queue and execution protocol

Execute tasks in the order below. For each task: create a task branch from the current integration/room-v2 head; put risks, affected SPEC IDs, and proving tests in the PR description before validation; implement one logical change; push; wait for CI (poll no more often than every 60 seconds); inspect failures; re-read the complete diff; search for stale references to removed symbols; record the task in WORKLOG.md; and squash-merge into integration/room-v2 only when all required CI checks are green.

- **T1 — Integrate room simplification.** Integrate refactor/nexus-simplification; remove the stale MainActivity notification-permission request and unused imports. Verify no stale POST_NOTIFICATIONS request remains in the relevant source/configuration. Repair malformed duplicate tests only by removing exact duplicate bodies and fixing compile-only fixture omissions. Lint, unit tests, and assembleDebug must pass.
- **T2 — Single publish path.** Remove publication from pollingLoop. Publish only through one debounced worker with transient-failure retry/backoff. Prove select+play coalescing and burst-seek single publication. Net line delta must be non-positive.
- **T3 — Specification and agent rules.** Commit SPEC.md and this AGENTS.md. Add CI protection that permits the initial SPEC.md bootstrap only while the base branch has no SPEC.md, then fails every later PR that changes SPEC.md.
- **T4 — System interruptions and local-first playback.** Implement S5 and S8. Delete forwardSystemPause and systemPauseForwarded. System pauses remain local-only; implement the rejoin rule; suspend drift correction while system-paused; add S5-focused tests.
- **T5 — Playback phase simplification.** Inventory old flags and transitions in the PR description. Replace pendingRoomTrackStart, shouldHoldRoomPlay, shouldRoomBePlaying, barrier* flags, and cross-class callbacks with one phase enum owned by the controller and exposed to the port through one function. Add no booleans. Controller and port net line delta must be at least -150 while preserving behavior in tests.
- **T6 — Test diet.** Tag every ServiceRoomControllerTest with its SPEC ID. Remove exact duplicate tests and private-implementation-detail tests only when named in the PR description. Do not edit assertions to force a pass. Target no more than 1,800 lines.
- **T7 — Room screen diet.** Keep only room code with copy, one connection-status line, chat, and Leave. Remove duplicate Play/pause/seek controls. Put diagnostics and Copy log behind a long-press on status. Visual result remains UNVERIFIED until inspected on device.
- **T8 — Dead-code removal.** Remove unreferenced functions such as RoomController.submit only after repository-wide caller search proves zero callers.
- **T9 — Diagnostics.** Include app version, device model, API level, wake mode, clock offset/RTT; per-action ID/type/intent time/publish acknowledgement/received/applied/resulting drift; drift samples every 60 seconds; barrier outcome and phase. Ring buffer maximum 300. Add formatter unit tests.
- **T10 — Updater audit (report only).** Audit every path that could publish or offer a debug-signed/untagged APK. An installed APK signed by a different key must report UNKNOWN rather than WRONG KEY. Change code only if a test proves a defect.
- **T11 — Branch/PR audit (report only).** Report stale branches and PRs, last commit dates, and whether their contents are already in integration. Delete nothing.

## Retry, merge, and final-report rules

- After three failed attempts on a task, revert that task, mark it BLOCKED in WORKLOG.md, and continue with the next independent task. T1 blocked is the only task-level stop condition.
- Never touch main during task execution. Never delete branches.
- Maintain one root WORKLOG.md row per task with status, commit, CI run/link, and a concise learning/root-cause note.
- When the queue is complete, create exactly one draft PR from integration/room-v2 to main. Do not merge it. Its body must be a final report of no more than 400 words, with a table containing task, DONE/BLOCKED/SKIPPED, commit, CI link, SPEC IDs, and net line delta; then list UNVERIFIED device behavior, DECISION-NEEDED items, and the two-phone test checklist.
