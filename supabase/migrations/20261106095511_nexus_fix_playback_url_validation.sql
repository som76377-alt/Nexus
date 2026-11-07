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
security definer
set search_path = pg_catalog
as $function$
declare
  v_user_id uuid := auth.uid();
  v_current_revision bigint;
  v_source_scheme text;
  v_source_host text;
  v_existing_action_id uuid;
  v_action_revision bigint;
  v_new_revision bigint;
begin
  if v_user_id is null then
    raise exception 'Authentication required';
  end if;
  if p_room_id is null then
    raise exception 'Room ID is required';
  end if;
  if p_expected_revision is null or p_expected_revision < 0 then
    raise exception 'Expected playback revision must be zero or greater';
  end if;
  if p_action_id is null then
    raise exception 'Playback action ID is required';
  end if;
  if p_action_type is null or p_action_type not in (
    'TRACK', 'PLAY', 'PAUSE', 'SEEK', 'RECOVERY', 'STATE'
  ) then
    raise exception 'Invalid playback action type';
  end if;
  if p_source_id is null or length(btrim(p_source_id)) = 0 or length(p_source_id) > 256 then
    raise exception 'Invalid playback source identifier';
  end if;
  if p_source_url is null or length(btrim(p_source_url)) = 0 or length(p_source_url) > 16384 then
    raise exception 'Invalid playback source URL';
  end if;
  v_source_scheme := lower(split_part(p_source_url, '://', 1));
  v_source_host := lower(split_part(split_part(p_source_url, '://', 2), '/', 1));
  if v_source_scheme <> 'https' or v_source_host not in (
    'youtube.com', 'www.youtube.com', 'm.youtube.com', 'music.youtube.com', 'youtu.be'
  ) then
    raise exception 'Playback source must be a canonical HTTPS YouTube URL';
  end if;
  if p_title is null or length(btrim(p_title)) = 0 or length(p_title) > 500 then
    raise exception 'Invalid playback title';
  end if;
  if p_artist is not null and length(p_artist) > 500 then
    raise exception 'Playback artist is too long';
  end if;
  if p_origin_device_id is not null and length(p_origin_device_id) > 128 then
    raise exception 'Origin device identifier is too long';
  end if;
  if p_position_ms is null or p_position_ms < 0 or
     p_duration_ms is null or p_duration_ms < 0 then
    raise exception 'Playback position and duration must be zero or greater';
  end if;

  -- Serialize joins, leaves, heartbeats and playback writes using the existing room lock.
  perform pg_advisory_xact_lock(
    hashtextextended('nexus-room:' || p_room_id::text, 0)
  );

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

  -- A retry remains idempotent even if another member's newer command is now current.
  select a.revision
  into v_action_revision
  from private.room_playback_actions a
  where a.room_id = p_room_id
    and a.action_id = p_action_id;

  if found then
    return query select true, v_action_revision;
    return;
  end if;

  select rp.revision, rp.action_id
  into v_current_revision, v_existing_action_id
  from public.room_playback rp
  where rp.room_id = p_room_id
  for update;

  if found and v_existing_action_id = p_action_id then
    -- Bridge retries for the action that was current immediately before this migration.
    insert into private.room_playback_actions(room_id, action_id, revision)
    values (p_room_id, p_action_id, v_current_revision)
    on conflict (room_id, action_id) do nothing;
    return query select true, v_current_revision;
    return;
  end if;

  if not found then
    if p_expected_revision <> 0 then
      return query select false, 0::bigint;
      return;
    end if;

    insert into public.room_playback as inserted(
      room_id, source_id, source_url, title, artist, is_playing, position_ms,
      duration_ms, updated_by, action_id, action_type, origin_device_id, command_at
    ) values (
      p_room_id, p_source_id, p_source_url, p_title, p_artist, p_is_playing,
      p_position_ms, p_duration_ms, v_user_id, p_action_id,
      p_action_type, p_origin_device_id, now()
    )
    returning inserted.revision into v_new_revision;
  else
    if v_current_revision <> p_expected_revision then
      return query select false, v_current_revision;
      return;
    end if;

    update public.room_playback as current_playback
    set source_id = p_source_id,
        source_url = p_source_url,
        title = p_title,
        artist = p_artist,
        is_playing = p_is_playing,
        position_ms = p_position_ms,
        duration_ms = p_duration_ms,
        updated_by = v_user_id,
        action_id = p_action_id,
        action_type = p_action_type,
        origin_device_id = p_origin_device_id,
        command_at = now()
    where room_id = p_room_id
    returning current_playback.revision into v_new_revision;
  end if;

  insert into private.room_playback_actions(room_id, action_id, revision)
  values (p_room_id, p_action_id, v_new_revision);

  -- Keep retries from the last day idempotent without retaining unbounded room history.
  delete from private.room_playback_actions
  where room_id = p_room_id
    and created_at < now() - interval '24 hours';

  return query select true, v_new_revision;
end;
$function$;

revoke all on function public.publish_room_playback(
  uuid, bigint, text, text, text, text, boolean, bigint, bigint, uuid, text, text
) from public, anon;
grant execute on function public.publish_room_playback(
  uuid, bigint, text, text, text, text, boolean, bigint, bigint, uuid, text, text
) to authenticated;