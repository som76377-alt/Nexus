-- Replace long hexadecimal room invitations with short, human-readable codes.
-- Keep accepting existing 16-character codes until those rooms naturally expire.
create or replace function public.create_room()
returns table(room_id uuid, room_code text)
language plpgsql
security definer
set search_path = pg_catalog, public, extensions, auth
as $$
declare
  v_user_id uuid := auth.uid();
  v_code text;
  v_room_id uuid;
  v_random bytea;
  v_alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
begin
  if v_user_id is null then
    raise exception 'Authentication required';
  end if;

  loop
    v_random := extensions.gen_random_bytes(5);
    v_code := '';
    for i in 0..4 loop
      v_code := v_code || substr(v_alphabet, (get_byte(v_random, i) % 32) + 1, 1);
    end loop;

    v_room_id := null;
    begin
      insert into public.rooms(code_hash, created_by)
      values (
        encode(extensions.digest(convert_to(v_code, 'UTF8'), 'sha256'), 'hex'),
        v_user_id
      )
      returning id into v_room_id;
      exit;
    exception when unique_violation then
      -- Generate another code if this room code is already in use.
    end;
  end loop;

  insert into public.room_members(room_id, user_id)
  values (v_room_id, v_user_id);

  return query select v_room_id, v_code;
end;
$$;

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

  -- New codes are uppercase and exclude easily confused characters. Legacy hex codes remain
  -- case-insensitive so rooms created by older app versions can finish their one-hour lifetime.
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
revoke all on function public.create_room() from public, anon;
grant execute on function public.create_room() to authenticated;
