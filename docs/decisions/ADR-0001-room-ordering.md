# ADR-0001: Keep database revision CAS as v1 ordering authority

- Status: Accepted for v1
- Date: 2026-11-06

## Decision

Keep the existing membership-checked Supabase RPC, monotonically increasing playback revision and idempotent action UUID as the only authoritative command-ordering mechanism.

Postgres Changes are wake-up signals. After a signal, clients fetch the authoritative row and apply only a newer revision.

## Why

- The live database already enforces the RPC-only write path.
- PR #6 improves local action protection, conflict handling and retry behavior around this mechanism.
- Adding Lamport ordering and a Broadcast command path at the same time would create two competing ordering authorities and make failures harder to diagnose.
- The current design can be measured on real devices before replacing its transport.

## Revisit when

Measured command latency or event delivery failures remain unacceptable after the service-owned controller is implemented and tested.

Any future Broadcast design must define membership authorization, concurrent command tie-breaks, durable command IDs, reconnect snapshots and atomic idempotent persistence before rollout.
