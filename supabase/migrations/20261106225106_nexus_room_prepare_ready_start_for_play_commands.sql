-- Allow both new-track and explicit-resume revisions to use the READY/START barrier.
-- Android calls this RPC for TRACK and PLAY actions. The former TRACK-only predicate
-- made a real resumed track fail even though the controller correctly waited at the barrier.

create or replace function public.mark_room_playback_ready(
  p_room_id uuid,
  p_revision bigint,
  p_source_id text
)
returns table(start_at timestamptz, active_members integer, ready_members integer)
language plpgsql
security definer
set search_path = pg_catalog
as $function$
declare
  v_user_id uuid := auth.uid();
  v_current_source_id text;
  v_current_revision bigint;
  v_action_type text;
  v_is_playing boolean;
  v_start_at timestamptz;
  v_active_members integer;
  v_ready_members integer;
begin
  if v_user_id is null then
    raise exception 'Authentication required';
  end if;
  if p_room_id is null or p_revision is null or p_revision < 1 or
     p_source_id is null or length(btrim(p_source_id)) = 0 then
    raise exception 'Invalid room playback readiness request';
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

  select rp.source_id, rp.revision, rp.action_type, rp.is_playing
  into v_current_source_id, v_current_revision, v_action_type, v_is_playing
  from public.room_playback rp
  where rp.room_id = p_room_id
  for update;

  if not found or v_current_revision <> p_revision or
     v_current_source_id <> p_source_id or
     v_action_type not in ('TRACK', 'PLAY') or not v_is_playing then
    raise exception 'Playback revision changed before this device became ready';
  end if;

  insert into private.room_playback_readiness(
    room_id, revision, source_id, user_id, ready_at, expires_at
  ) values (
    p_room_id, p_revision, p_source_id, v_user_id,
    clock_timestamp(), clock_timestamp() + interval '45 seconds'
  )
  on conflict (room_id, revision, user_id)
  do update set
    source_id = excluded.source_id,
    ready_at = excluded.ready_at,
    expires_at = excluded.expires_at;

  delete from private.room_playback_readiness
  where room_id = p_room_id
    and expires_at < clock_timestamp() - interval '1 hour';

  -- Preserve the current revision's original synchronized timeline for reconnecting peers.
  delete from private.room_playback_starts
  where room_id = p_room_id
    and revision <> p_revision;

  select greatest(count(*)::integer, 1)
  into v_active_members
  from public.room_members rm
  where rm.room_id = p_room_id
    and rm.last_seen_at > clock_timestamp() - interval '45 seconds';

  select count(*)::integer
  into v_ready_members
  from private.room_playback_readiness rr
  join public.room_members rm
    on rm.room_id = rr.room_id and rm.user_id = rr.user_id
  where rr.room_id = p_room_id
    and rr.revision = p_revision
    and rr.source_id = p_source_id
    and rr.expires_at > clock_timestamp()
    and rm.last_seen_at > clock_timestamp() - interval '45 seconds';

  if v_ready_members >= v_active_members then
    insert into private.room_playback_starts(room_id, revision, source_id, start_at)
    values (
      p_room_id,
      p_revision,
      p_source_id,
      clock_timestamp() + interval '1500 milliseconds'
    )
    on conflict (room_id, revision) do nothing;
  end if;

  select s.start_at
  into v_start_at
  from private.room_playback_starts s
  where s.room_id = p_room_id
    and s.revision = p_revision
    and s.source_id = p_source_id;

  return query select v_start_at, v_active_members, v_ready_members;
end;
$function$;

revoke all on function public.mark_room_playback_ready(uuid, bigint, text) from public, anon;
grant execute on function public.mark_room_playback_ready(uuid, bigint, text) to authenticated;
