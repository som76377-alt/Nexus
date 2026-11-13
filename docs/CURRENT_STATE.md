# Nexus current state (J0 baseline)

This table separates repository/CI state from physical-device evidence. Green CI is not a phone test.

| Area | Repository state | CI evidence | Device evidence |
|---|---|---|---|
| R0 NewPipeExtractor baseline | Merged to main (7c53cb1; R0a/R0b) | Android CI #717 passed | UNVERIFIED — no timestamped Provider Lab report for either phone on mobile data or Wi-Fi |
| R2 provider resolver chain | Merged to main (9bdc017) | Android CI #768 and Web Room Test #350 passed | UNVERIFIED — live NewPipe/Piped/Invidious routing and real network fallback |
| R3 stream probe and local failover | Merged to main (162d3e0) | Android CI #792 and Web Room Test #364 passed | UNVERIFIED — live probe, failover, IP-family behavior, and two-phone timing |
| R4 persistent offline cache | Incomplete; r4/offline-audio-cache has no production metadata lookup integration or user controls | Exact-head CI required after J0 | UNVERIFIED — offline replay |
| Updater / signed release path | Existing release tag is v0.1.8; R2/R3 are not in that release | Prior updater audit only; no new release validation | UNVERIFIED — same-key in-place update on both phones |
| Failover UX / error classification | R3 recovery needs J2 hardening and real-wiring proof | Not yet validated for J2 | UNVERIFIED — exhaustion/retry and recovery latency |
| Remote config / extractor patch pipeline | Not implemented | Not tested | UNVERIFIED |
| yt-dlp feasibility | Not evaluated | Not tested | UNVERIFIED |
| G1–G6 overall | No goal is certified bulletproof | CI alone cannot certify these goals | UNVERIFIED — Som's timestamped two-phone checklist is required |

## Job queue status

| Job | Status |
|---|---|
| J0 truth, tripwires, fast feedback | In progress |
| J1 offline-capable persistent cache | Not started |
| J2 failover hardening | Not started |
| J3 Provider Lab chaos switches | Not started |
| J4 remote config, patch pipeline, updater gate | Not started |
| J5 yt-dlp feasibility spike | Blocked until J1–J4 are merged |
| J6 four Provider Lab reports | Needs Som's phone evidence |
| J7 acceptance matrix / soak support | Not started |
| J8 release candidate | Requires J0–J4 and J7; stop for explicit RELEASE |
| J9 checklist close-out | Needs Som's checklist results |
