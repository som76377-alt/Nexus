-- Return the active participant count without exposing anonymous user IDs.
-- Access is limited to authenticated users whose membership in this room is current.

create or replace function public.get_room_active_member_count(p_room_id uuid)
returns integer
language plpgsql
security definer
set search_path = pg_catalog
as $function$
declare
  v_user_id uuid := auth.uid();
  v_active_members integer;
begin
  if v_user_id is null then
    raise exception 'Authentication required';
  end if;
  if p_room_id is null then
    raise exception 'Room ID is required';
  end if;

  if not exists (
    select 1
    from public.room_members rm
    join public.rooms r on r.id = rm.room_id
    where rm.room_id = p_room_id
      and rm.user_id = v_user_id
      and r.last_activity_at > now() - interval '1 hour'
  ) then
    raise exception 'Room membership is missing or expired';
  end if;

  select count(*)::integer
  into v_active_members
  from public.room_members rm
  where rm.room_id = p_room_id
    and rm.last_seen_at > clock_timestamp() - interval '45 seconds';

  return greatest(v_active_members, 1);
end;
$function$;

revoke all on function public.get_room_active_member_count(uuid) from public, anon;
grant execute on function public.get_room_active_member_count(uuid) to authenticated;
