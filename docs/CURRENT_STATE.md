# Nexus current state (J1 implementation in progress)

This table separates repository/CI state from physical-device evidence. Green CI is not a phone test.

| Area | Repository state | CI evidence | Device evidence |
|---|---|---|---|
| R0 NewPipeExtractor baseline | Merged to main (7c53cb1; R0a/R0b) | Android CI #717 passed | UNVERIFIED — no timestamped Provider Lab report for either phone on mobile data or Wi-Fi |
| R2 provider resolver chain | Merged to main (9bdc017) | Android CI #768 and Web Room Test #350 passed | UNVERIFIED — live NewPipe/Piped/Invidious routing and real network fallback |
| R3 stream probe and local failover | Merged to main (162d3e0) | Android CI #792 and Web Room Test #364 passed | UNVERIFIED — live probe, failover, IP-family behavior, and two-phone timing |
| R4 persistent offline cache | J1 branch now wires persistent Media3 byte cache + videoId/itag metadata into normal and room resolvers; configurable LRU cap and You-screen controls added; test coverage and final validation still in progress | J1 integration is in PR #79: persistent Media3 bytes + metadata are wired into normal and room resolvers; configurable LRU cap and You-screen controls are present; J1 PR #79 is a single consolidated implementation commit at 4c00bd1; exact-head Android CI and Web Room Test are pending | UNVERIFIED — airplane-mode replay and storage behavior on physical device |
| Updater / signed release path | Existing release tag is v0.1.8; R2/R3 are not in that release | Prior updater audit only; no new release validation | UNVERIFIED — same-key in-place update on both phones |
| Failover UX / error classification | R3 recovery needs J2 hardening and real-wiring proof | Not yet validated for J2 | UNVERIFIED — exhaustion/retry and recovery latency |
| Remote config / extractor patch pipeline | Not implemented | Not tested | UNVERIFIED |
| yt-dlp feasibility | Not evaluated | Not tested | UNVERIFIED |
| G1–G6 overall | No goal is certified bulletproof | CI alone cannot certify these goals | UNVERIFIED — Som's timestamped two-phone checklist is required |

## Job queue status

| Job | Status |
|---|---|
| J0 truth, tripwires, fast feedback | Merged to main (002473b) |
| J1 offline-capable persistent cache | In progress — PR #79, single commit 4c00bd1; exact-head CI pending |
| J2 failover hardening | Not started |
| J3 Provider Lab chaos switches | Not started |
| J4 remote config, patch pipeline, updater gate | Not started |
| J5 yt-dlp feasibility spike | Blocked until J1–J4 are merged |
| J6 four Provider Lab reports | Needs Som's phone evidence |
| J7 acceptance matrix / soak support | Not started |
| J8 release candidate | Requires J0–J4 and J7; stop for explicit RELEASE |
| J9 checklist close-out | Needs Som's checklist results |
