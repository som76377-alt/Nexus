# Nexus integration work log

| Task | Status | Commit | CI run | Learning |
|---|---|---|---|---|
| T1 integrate room simplification and remove stale notification permission request | DONE | d3e5fd06393e65918ff7be2c76d479f1319d85cc | [Android CI](https://github.com/som76377-alt/Nexus/actions/runs/42364696177) | Lint, unit tests, assembleDebug and release guard passed. Root causes: stale permission method; 3 appended duplicate test/helper blocks; two SelectTrack fixtures missing artist. Exact duplicate bodies removed; assertions unchanged. |
| T2 single publish path with backoff and seek coalescing | DONE | 9620af8b50f7b61be5a2030eb3dc56c404a3bf36 | [Android CI](https://github.com/som76377-alt/Nexus/actions/runs/42366027758); [Web room tests](https://github.com/som76377-alt/Nexus/actions/runs/42366027761) | Polling no longer publishes. One serialized worker handles intents and RECOVERY, coalesces seek bursts, and retries transient failures with capped exponential backoff. Android lint, unit tests, debug APK assembly, unsigned-release guard, and web room tests passed. Diff: 108 additions / 109 deletions (net -1). |
