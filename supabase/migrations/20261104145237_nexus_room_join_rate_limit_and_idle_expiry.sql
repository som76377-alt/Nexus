-- Make join attempt rate limiting durable: failed attempts must return data rather than
-- raise an exception, because a raised exception rolls back the attempt row.
drop function if exists public.join_room(text);

create function public.join_room(p_code text)
returns table(room_id uuid, error text)
language plpgsql
security definer
set search_path = pg_catalog, public, extensions, auth, private
as $$
declare
  v_user_id uuid := auth.uid();
  v_room_id uuid;
  v_code text := lower(btrim(coalesce(p_code, '')));
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

  if v_code !~ '^[0-9a-f]{16}$' then
    return query select null::uuid, 'Invalid or expired room code'::text;
    return;
  end if;

  select r.id into v_room_id
  from public.rooms r
  where r.code_hash = encode(extensions.digest(convert_to(v_code, 'UTF8'), 'sha256'), 'hex')
    and r.last_activity_at > now() - interval '1 hour'
  for update;

  if v_room_id is null then
    return query select null::uuid, 'Invalid or expired room code'::text;
    return;
  end if;

  if exists (
    select 1 from public.room_members
    where public.room_members.room_id = v_room_id
      and public.room_members.user_id = v_user_id
  ) then
    update public.room_members
    set last_seen_at = now()
    where public.room_members.room_id = v_room_id
      and public.room_members.user_id = v_user_id;
    update public.rooms set last_activity_at = now() where id = v_room_id;
    return query select v_room_id, null::text;
    return;
  end if;

  select count(*) into v_member_count
  from public.room_members where public.room_members.room_id = v_room_id;

  if v_member_count >= 2 then
    return query select null::uuid, 'This room already has two members'::text;
    return;
  end if;

  insert into public.room_members(room_id, user_id) values (v_room_id, v_user_id);
  update public.rooms set last_activity_at = now() where id = v_room_id;
  return query select v_room_id, null::text;
end;
$$;

revoke all on function public.join_room(text) from public, anon;
grant execute on function public.join_room(text) to authenticated;

-- A room is no longer usable after its one-hour idle deadline, even during the
-- five-minute interval before the scheduled cleanup physically deletes it.
create or replace function public.touch_room(p_room_id uuid)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  v_user_id uuid := auth.uid();
begin
  if v_user_id is null then raise exception 'Authentication required'; end if;

  update public.room_members rm
  set last_seen_at = now()
  where rm.room_id = p_room_id
    and rm.user_id = v_user_id
    and exists (
      select 1 from public.rooms r
      where r.id = rm.room_id
        and r.last_activity_at > now() - interval '1 hour'
    );

  if not found then return false; end if;
  update public.rooms set last_activity_at = now() where id = p_room_id;
  return true;
end;
$$;
revoke all on function public.touch_room(uuid) from public, anon;
grant execute on function public.touch_room(uuid) to authenticated;

drop policy if exists "room members can view their rooms" on public.rooms;
create policy "room members can view their active rooms"
on public.rooms for select to authenticated
using (
  last_activity_at > now() - interval '1 hour'
  and exists (
    select 1 from public.room_members rm
    where rm.room_id = rooms.id and rm.user_id = (select auth.uid())
  )
);

drop policy if exists "members can read their room chat" on public.room_messages;
create policy "members can read active room chat"
on public.room_messages for select to authenticated
using (
  exists (
    select 1
    from public.room_members rm
    join public.rooms r on r.id = rm.room_id
    where rm.room_id = room_messages.room_id
      and rm.user_id = (select auth.uid())
      and r.last_activity_at > now() - interval '1 hour'
  )
);

drop policy if exists "members can send as themselves" on public.room_messages;
create policy "members can send to active rooms as themselves"
on public.room_messages for insert to authenticated
with check (
  sender_id = (select auth.uid())
  and exists (
    select 1
    from public.room_members rm
    join public.rooms r on r.id = rm.room_id
    where rm.room_id = room_messages.room_id
      and rm.user_id = (select auth.uid())
      and r.last_activity_at > now() - interval '1 hour'
  )
);

drop policy if exists "members can read room playback" on public.room_playback;
create policy "members can read active room playback"
on public.room_playback for select to authenticated
using (
  exists (
    select 1
    from public.room_members rm
    join public.rooms r on r.id = rm.room_id
    where rm.room_id = room_playback.room_id
      and rm.user_id = (select auth.uid())
      and r.last_activity_at > now() - interval '1 hour'
  )
);

drop policy if exists "members can create room playback" on public.room_playback;
create policy "members can create playback in active rooms"
on public.room_playback for insert to authenticated
with check (
  updated_by = (select auth.uid())
  and exists (
    select 1
    from public.room_members rm
    join public.rooms r on r.id = rm.room_id
    where rm.room_id = room_playback.room_id
      and rm.user_id = (select auth.uid())
      and r.last_activity_at > now() - interval '1 hour'
  )
);

drop policy if exists "members can update room playback" on public.room_playback;
create policy "members can update playback in active rooms"
on public.room_playback for update to authenticated
using (
  exists (
    select 1
    from public.room_members rm
    join public.rooms r on r.id = rm.room_id
    where rm.room_id = room_playback.room_id
      and rm.user_id = (select auth.uid())
      and r.last_activity_at > now() - interval '1 hour'
  )
)
with check (
  updated_by = (select auth.uid())
  and exists (
    select 1
    from public.room_members rm
    join public.rooms r on r.id = rm.room_id
    where rm.room_id = room_playback.room_id
      and rm.user_id = (select auth.uid())
      and r.last_activity_at > now() - interval '1 hour'
  )
);
