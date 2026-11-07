# Architecture constraints

There is no separate future architecture to build in parallel. Use [ARCHITECTURE.md](ARCHITECTURE.md) as the source of truth.

Keep the current single-module Android app, one Media3 player, service-owned room controller, existing Supabase/OkHttp transport and NewPipe resolver. Do not introduce another player, backend, event-ordering system, Gradle module split or background wake mechanism without a measured need and a focused decision.

Keep package boundaries, but do not add interfaces or wrappers for a single implementation unless they are actively used, tested seams. Prefer one clear production path over speculative extensibility.
