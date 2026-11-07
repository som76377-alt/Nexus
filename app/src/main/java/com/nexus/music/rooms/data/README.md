# Room data adapters

The data package adapts the existing authenticated REST/RPC and Realtime clients to the player-neutral room domain.

- `SupabaseRoomRepository` maps playback snapshots and revision-checked commands through the existing `RoomsApi`.
- The repository adapts `RoomRealtimeClient` into authoritative playback snapshots, scheduled-start signals, chat invalidation and connection-state signals.
- `SupabaseRoomClock` reuses the existing five-probe server-clock calibration.

Rules:

1. Reuse the existing OkHttp-based REST/Auth client and Phoenix WebSocket implementation.
2. Do not add supabase-kt or a second networking stack.
3. Validate every Realtime snapshot's room ID and revision; use REST reads for reconnect and recovery.
4. Do not let chat inserts emit playback invalidation signals.
5. Never embed a service-role key or bypass the membership-checked RPC.
6. Do not modify live schema/RLS from app code.
