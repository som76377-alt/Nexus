-- Remove broader default grants and restore only the exact permissions used by the app.
-- RLS remains enabled on all exposed public room tables.
revoke all privileges on table
  public.rooms,
  public.room_members,
  public.room_messages,
  public.room_playback
from public, anon, authenticated;

grant select on public.rooms to authenticated;
grant select on public.room_members to authenticated;
grant select, insert on public.room_messages to authenticated;
grant select, insert, update on public.room_playback to authenticated;

-- The join-attempt table is internal to SECURITY DEFINER RPCs and is not an API surface.
revoke all privileges on table private.room_join_attempts from public, anon, authenticated;
