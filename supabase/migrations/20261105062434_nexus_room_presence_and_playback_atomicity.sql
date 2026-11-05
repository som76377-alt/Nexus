-- Keep room membership recoverable after a device disappears, and serialize
-- membership / playback writes so the first shared playback snapshot cannot race.
--
-- The three-minute lease is deliberately longer than the 25-second client heartbeat.
-- A live member can reclaim their own row; a new member can replace only a different,
-- demonstrably stale device identity.

create or replace function public.join_room(p_code text)
returns table(room_id uuid, error text)
language plpgsql
security definer
set search_path = pg_catalog, public, extensions, auth, private
as $$
declare
  v_user_id uuid := auth.uid();
  v_room_id uuid;
  v_code text := btrim(coalesce(p_code, ''));
  v_member_count integer;
  v_attempt_count integer;
begin
  if v_user_id is null then
    raise exception 'Authentication required';
  end if;

  insert into private.room_join_attempts(user_id) values (v_user_id);
  select count(*) into v_attempt_count
  from private.room_join_attempts
  where user_id = v_user_id
    and attempted_at > now() - interval '10 minutes';

  if v_attempt_count > 10 then
    return query select null::uuid, 'Too many attempts. Wait a few minutes and try again.'::text;
    return;
  end if;

  -- Keep compatibility with existing 16-character hexadecimal room codes.
  if v_code ~ '^[0-9a-fA-F]{16}$' then
    v_code := lower(v_code);
  else
    v_code := upper(v_code);
  end if;

  if v_code !~ '^[A-HJ-NP-Z2-9]{5}$' and v_code !~ '^[0-9a-f]{16}$' then
    return query select null::uuid, 'Invalid or expired room code'::text;
    return;
  end if;

  select r.id into v_room_id
  from public.rooms r
  where r.code_hash = encode(
      extensions.digest(convert_to(v_code, 'UTF8'), 'sha256'),
      'hex'
    )
    and r.last_activity_at > now() - interval '1 hour';

  if v_room_id is null then
    return query select null::uuid, 'Invalid or expired room code'::text;
    return;
  end if;

  -- One per-room transaction lock serializes joins, exits, heartbeats and playback
  -- writes. This also protects the "no room_playback row exists yet" first-write case.
  perform pg_advisory_xact_lock(
    hashtextextended('nexus-room:' || v_room_id::text, 0)
  );

  -- The room may have expired while this request waited for the per-room lock.
  if not exists (
    select 1 from public.rooms r
    where r.id = v_room_id
      and r.last_activity_at > now() - interval '1 hour'
  ) then
    return query select null::uuid, 'Invalid or expired room code'::text;
    return;
  end if;

  -- A returning device keeps its original membership, even if it missed heartbeats.
  if exists (
    select 1 from public.room_members rm
    where rm.room_id = v_room_id and rm.user_id = v_user_id
  ) then
    update public.room_members
    set last_seen_at = now()
    where room_members.room_id = v_room_id
      and room_members.user_id = v_user_id;
    update public.rooms set last_activity_at = now() where id = v_room_id;
    return query select v_room_id, null::text;
    return;
  end if;

  -- Anonymous Auth identities are device-local. Reclaim a seat left behind by an
  -- uninstalled / cleared / replaced device, but preserve members seen in the last
  -- three minutes so a normal reconnect is not mistaken for a departed member.
  delete from public.room_members rm
  where rm.room_id = v_room_id
    and rm.user_id <> v_user_id
    and rm.last_seen_at < now() - interval '3 minutes';

  select count(*) into v_member_count
  from public.room_members rm
  where rm.room_id = v_room_id;

  if v_member_count >= 2 then
    return query select null::uuid, 'This room already has two members'::text;
    return;
  end if;

  insert into public.room_members(room_id, user_id)
  values (v_room_id, v_user_id);
  update public.rooms set last_activity_at = now() where id = v_room_id;

  return query select v_room_id, null::text;
end;
$$;

revoke all on function public.join_room(text) from public, anon;
grant execute on function public.join_room(text) to authenticated;


-- Explicit Exit Room releases the membership immediately instead of holding a
-- seat until the next heartbeat timeout or whole-room expiry.
create or replace function public.leave_room(p_room_id uuid)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  v_user_id uuid := auth.uid();
begin
  if v_user_id is null then
    raise exception 'Authentication required';
  end if;
  if p_room_id is null then
    return false;
  end if;

  perform pg_advisory_xact_lock(
    hashtextextended('nexus-room:' || p_room_id::text, 0)
  );

  delete from public.room_members
  where room_members.room_id = p_room_id
    and room_members.user_id = v_user_id;

  return found;
end;
$$;

revoke all on function public.leave_room(uuid) from public, anon;
grant execute on function public.leave_room(uuid) to authenticated;


-- Serialize playback publication with room joins, leaves and heartbeats. Without this
-- lock, two phones can both observe that a room has no playback row and race to insert
-- the same room_id primary key. The CAS revision still makes later commands ordered.
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
  if v_user_id is null then
    raise exception 'Authentication required';
  end if;
  if p_room_id is null then
    raise exception 'Room ID is required';
  end if;
  if p_action_type not in ('TRACK', 'PLAY', 'PAUSE', 'SEEK', 'RECOVERY', 'STATE') then
    raise exception 'Invalid playback action type';
  end if;

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

  select rp.revision, rp.action_id
  into v_current_revision, v_existing_action_id
  from public.room_playback rp
  where rp.room_id = p_room_id
  for update;

  if not found then
    if coalesce(p_expected_revision, 0) <> 0 then
      return query select false, 0::bigint;
      return;
    end if;

    insert into public.room_playback(
      room_id, source_id, source_url, title, artist, is_playing, position_ms,
      duration_ms, updated_by, action_id, action_type, origin_device_id, command_at
    ) values (
      p_room_id, p_source_id, p_source_url, p_title, p_artist, p_is_playing,
      greatest(coalesce(p_position_ms, 0), 0),
      greatest(coalesce(p_duration_ms, 0), 0),
      v_user_id, coalesce(p_action_id, gen_random_uuid()), p_action_type,
      p_origin_device_id, now()
    );

    return query select true, 1::bigint;
    return;
  end if;

  -- If the client timed out after a commit, the same action remains idempotent.
  if p_action_id is not null and v_existing_action_id = p_action_id then
    return query select true, v_current_revision;
    return;
  end if;

  if v_current_revision <> p_expected_revision then
    return query select false, v_current_revision;
    return;
  end if;

  update public.room_playback
  set source_id = p_source_id,
      source_url = p_source_url,
      title = p_title,
      artist = p_artist,
      is_playing = p_is_playing,
      position_ms = greatest(coalesce(p_position_ms, 0), 0),
      duration_ms = greatest(coalesce(p_duration_ms, 0), 0),
      updated_by = v_user_id,
      action_id = coalesce(p_action_id, gen_random_uuid()),
      action_type = p_action_type,
      origin_device_id = p_origin_device_id,
      command_at = now()
  where room_id = p_room_id;

  -- The existing BEFORE UPDATE trigger increments revision and stamps updated_at.
  return query select true, v_current_revision + 1;
end;
$$;

revoke all on function public.publish_room_playback(
  uuid, bigint, text, text, text, text, boolean, bigint, bigint, uuid, text, text
) from public, anon;
grant execute on function public.publish_room_playback(
  uuid, bigint, text, text, text, text, boolean, bigint, bigint, uuid, text, text
) to authenticated;


-- Prevent a heartbeat racing a seat-reclaim or explicit leave operation.
create or replace function public.touch_room(p_room_id uuid)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  v_user_id uuid := auth.uid();
begin
  if v_user_id is null then
    raise exception 'Authentication required';
  end if;
  if p_room_id is null then
    return false;
  end if;

  perform pg_advisory_xact_lock(
    hashtextextended('nexus-room:' || p_room_id::text, 0)
  );

  update public.room_members rm
  set last_seen_at = now()
  where rm.room_id = p_room_id
    and rm.user_id = v_user_id
    and exists (
      select 1 from public.rooms r
      where r.id = rm.room_id
        and r.last_activity_at > now() - interval '1 hour'
    );

  if not found then
    return false;
  end if;

  update public.rooms
  set last_activity_at = now()
  where id = p_room_id;

  return true;
end;
$$;

revoke all on function public.touch_room(uuid) from public, anon;
grant execute on function public.touch_room(uuid) to authenticated;
