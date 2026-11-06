# Room data adapters

This package will contain concrete adapters for the domain ports.

Planned responsibilities:

- Adapt the existing `RoomsApi` REST/RPC methods to `RoomRepository`.
- Adapt the existing `RoomRealtimeClient` to playback invalidation signals.
- Map database rows and RPC responses to domain models.
- Keep auth/session refresh, membership checks and HTTP error handling here.

Rules:

1. Reuse the existing OkHttp-based REST/Auth client and Phoenix WebSocket implementation.
2. Do not add supabase-kt or a second networking stack.
3. Do not treat Realtime payloads as authoritative playback state; fetch the snapshot.
4. Do not let chat inserts emit playback invalidation signals.
5. Never embed a service-role key or bypass the membership-checked RPC.
6. Do not modify live schema/RLS from app code.
