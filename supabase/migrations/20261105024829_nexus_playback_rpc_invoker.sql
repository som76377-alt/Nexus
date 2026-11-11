-- Playback writes do not need elevated privileges: RLS already grants members
-- the exact row operations used by this function. Keep the RPC invoker-scoped.
create or replace function public.publish_room_playback(
  p_room_id uuid,
  p_expected_revision bigint,
  p_source_id text,
  p_source_url text,
  p_title text,
  p_artist text,
  p_is_playing boolean,
  p_position_ms bigint,
  p_duration_ms bigint,
  p_action_id uuid,
  p_action_type text,
  p_origin_device_id text
)
returns table(applied boolean, revision bigint)
language plpgsql
security invoker
set search_path = pg_catalog, public, auth
as $$
declare
  v_user_id uuid := auth.uid();
  v_current_revision bigint;
  v_existing_action_id uuid;
begin
  if v_user_id is null then raise exception 'Authentication required'; end if;
  if p_action_type not in ('TRACK', 'PLAY', 'PAUSE', 'SEEK', 'RECOVERY', 'STATE') then
    raise exception 'Invalid playback action type';
  end if;
  if not exists (
    select 1 from public.room_members rm
    join public.rooms r on r.id = rm.room_id
    where rm.room_id = p_room_id and rm.user_id = v_user_id
      and r.last_activity_at > now() - interval '1 hour'
  ) then raise exception 'Room membership is missing or expired'; end if;

  select rp.revision, rp.action_id
  into v_current_revision, v_existing_action_id
  from public.room_playback rp
  where rp.room_id = p_room_id
  for update;

  if not found then
    if coalesce(p_expected_revision, 0) <> 0 then
      return query select false, 0::bigint; return;
    end if;
    insert into public.room_playback(
      room_id, source_id, source_url, title, artist, is_playing, position_ms,
      duration_ms, updated_by, action_id, action_type, origin_device_id, command_at
    ) values (
      p_room_id, p_source_id, p_source_url, p_title, p_artist, p_is_playing,
      greatest(coalesce(p_position_ms, 0), 0), greatest(coalesce(p_duration_ms, 0), 0),
      v_user_id, coalesce(p_action_id, gen_random_uuid()), p_action_type,
      p_origin_device_id, now()
    );
    return query select true, 1::bigint; return;
  end if;

  -- If the client timed out after the server committed, the same action ID is idempotent.
  if p_action_id is not null and v_existing_action_id = p_action_id then
    return query select true, v_current_revision; return;
  end if;

  if v_current_revision <> p_expected_revision then
    return query select false, v_current_revision; return;
  end if;

  update public.room_playback
  set source_id = p_source_id, source_url = p_source_url, title = p_title,
      artist = p_artist, is_playing = p_is_playing,
      position_ms = greatest(coalesce(p_position_ms, 0), 0),
      duration_ms = greatest(coalesce(p_duration_ms, 0), 0),
      updated_by = v_user_id, action_id = coalesce(p_action_id, gen_random_uuid()),
      action_type = p_action_type, origin_device_id = p_origin_device_id,
      command_at = now()
  where room_id = p_room_id;

  return query select true, v_current_revision + 1;
end;
$$;

revoke all on function public.publish_room_playback(
  uuid, bigint, text, text, text, text, boolean, bigint, bigint, uuid, text, text
) from public, anon;
grant execute on function public.publish_room_playback(
  uuid, bigint, text, text, text, text, boolean, bigint, bigint, uuid, text, text
) to authenticated;
