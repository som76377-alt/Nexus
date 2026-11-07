# ADR-0001: Keep database revision CAS as v1 ordering authority

- Status: Accepted for v1
- Date: 2026-11-06

## Decision

Keep the membership-checked Supabase RPC, monotonically increasing playback revision and idempotent action UUID as the only authoritative command-ordering mechanism.

Private Realtime Broadcast carries the authoritative row snapshot and scheduled-start signal for low-latency delivery. It does not order commands or replace the database revision. Clients validate room identity and apply only newer revisions; authenticated REST reads remain the reconnect and recovery path. Chat uses a separate membership-protected Postgres Changes subscription.

## Why

- The live database already enforces the RPC-only write path.
- PR #6 improves local action protection, conflict handling and retry behavior around this mechanism.
- A separate Broadcast command path would create a second ordering authority. Broadcast is therefore used only to deliver database-committed state, never to accept commands.
- The current design can be measured on real devices before replacing its transport.

## Revisit when

Measured command latency or event delivery failures remain unacceptable after the service-owned controller is implemented and tested.

Revisit only if measured database-committed broadcast delivery remains insufficient; do not turn Broadcast into an independent command-write path.
