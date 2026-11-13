# Nexus current state (J2 implementation in progress)

This table separates repository/CI state from physical-device evidence. Green CI is not a phone test.

| Area | Repository state | CI evidence | Device evidence |
|---|---|---|---|
| R0 NewPipeExtractor baseline | Merged to main (7c53cb1; R0a/R0b) | Android CI #717 passed | UNVERIFIED — no timestamped Provider Lab report for either phone on mobile data or Wi-Fi |
| R2 provider resolver chain | Merged to main (9bdc017) | Android CI #768 and Web Room Test #350 passed | UNVERIFIED — live NewPipe/Piped/Invidious routing and real network fallback |
| R3 stream probe and local failover | Merged to main (162d3e0) | Android CI #792 and Web Room Test #364 passed | UNVERIFIED — live probe, failover, IP-family behavior, and two-phone timing |
| R4 persistent offline cache | J1 persistent Media3 byte cache + videoId/itag metadata merged to main in squash commit bddd727; configurable LRU cap and You-screen controls included | Android CI #814 and Web Room Test #386 passed on exact tested PR head 8bc2574 | UNVERIFIED — airplane-mode replay and storage behavior on physical device |
| Updater / signed release path | Existing release tag is v0.1.8; R2/R3 are not in that release | Prior updater audit only; no new release validation | UNVERIFIED — same-key in-place update on both phones |
| Failover UX / error classification | J2 branch jobs/j2-failover-hardening adds strict recoverable-error classification, local-only retry wiring, and one status line + Retry after exhaustion | Exact-head Android CI and Web Room Test pending | UNVERIFIED — physical two-phone failover, room publication suppression, and resume within 1 second |
| Remote config / extractor patch pipeline | Not implemented | Not tested | UNVERIFIED |
| yt-dlp feasibility | Not evaluated | Not tested | UNVERIFIED |
| G1–G6 overall | No goal is certified bulletproof | CI alone cannot certify these goals | UNVERIFIED — Som's timestamped two-phone checklist is required |

## Job queue status

| Job | Status |
|---|---|
| J0 truth, tripwires, fast feedback | Merged to main (002473b) |
| J1 offline-capable persistent cache | Merged to main (bddd727) |
| J2 failover hardening | In progress — jobs/j2-failover-hardening |
| J3 Provider Lab chaos switches | Not started |
| J4 remote config, patch pipeline, updater gate | Not started |
| J5 yt-dlp feasibility spike | Blocked until J1–J4 are merged |
| J6 four Provider Lab reports | Needs Som's phone evidence |
| J7 acceptance matrix / soak support | Not started |
| J8 release candidate | Requires J0–J4 and J7; stop for explicit RELEASE |
| J9 checklist close-out | Needs Som's checklist results |
