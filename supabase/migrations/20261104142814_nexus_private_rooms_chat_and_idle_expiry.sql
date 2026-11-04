-- Private two-person listening rooms with room-scoped chat and idle expiry.
create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

create table public.rooms (
  id uuid primary key default gen_random_uuid(),
  code_hash text not null unique,
  created_by uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  last_activity_at timestamptz not null default now()
);

create table public.room_members (
  room_id uuid not null references public.rooms(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  joined_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  primary key (room_id, user_id)
);
create index room_members_user_id_idx on public.room_members(user_id);

create table public.room_messages (
  id uuid primary key default gen_random_uuid(),
  room_id uuid not null references public.rooms(id) on delete cascade,
  sender_id uuid not null references auth.users(id) on delete cascade,
  body text not null check (char_length(btrim(body)) between 1 and 2000),
  created_at timestamptz not null default now()
);
create index room_messages_room_created_idx on public.room_messages(room_id, created_at desc);

create table public.room_playback (
  room_id uuid primary key references public.rooms(id) on delete cascade,
  source_id text not null,
  source_url text not null,
  title text not null,
  artist text,
  is_playing boolean not null default false,
  position_ms bigint not null default 0 check (position_ms >= 0),
  duration_ms bigint not null default 0 check (duration_ms >= 0),
  revision bigint not null default 1,
  updated_by uuid not null references auth.users(id) on delete cascade,
  updated_at timestamptz not null default now()
);

create table private.room_join_attempts (
  user_id uuid not null references auth.users(id) on delete cascade,
  attempted_at timestamptz not null default now()
);
create index room_join_attempts_user_time_idx on private.room_join_attempts(user_id, attempted_at desc);

alter table public.rooms enable row level security;
alter table public.room_members enable row level security;
alter table public.room_messages enable row level security;
alter table public.room_playback enable row level security;

grant select on public.rooms to authenticated;
grant select on public.room_members to authenticated;
grant select, insert on public.room_messages to authenticated;
grant select, insert, update on public.room_playback to authenticated;

create policy "room members can view their rooms"
on public.rooms for select to authenticated
using (exists (
  select 1 from public.room_members rm
  where rm.room_id = rooms.id and rm.user_id = (select auth.uid())
));

create policy "members can view room membership"
on public.room_members for select to authenticated
using (exists (
  select 1 from public.room_members mine
  where mine.room_id = room_members.room_id and mine.user_id = (select auth.uid())
));

create policy "members can read their room chat"
on public.room_messages for select to authenticated
using (exists (
  select 1 from public.room_members rm
  where rm.room_id = room_messages.room_id and rm.user_id = (select auth.uid())
));

create policy "members can send as themselves"
on public.room_messages for insert to authenticated
with check (
  sender_id = (select auth.uid())
  and exists (
    select 1 from public.room_members rm
    where rm.room_id = room_messages.room_id and rm.user_id = (select auth.uid())
  )
);

create policy "members can read room playback"
on public.room_playback for select to authenticated
using (exists (
  select 1 from public.room_members rm
  where rm.room_id = room_playback.room_id and rm.user_id = (select auth.uid())
));

create policy "members can create room playback"
on public.room_playback for insert to authenticated
with check (
  updated_by = (select auth.uid())
  and exists (
    select 1 from public.room_members rm
    where rm.room_id = room_playback.room_id and rm.user_id = (select auth.uid())
  )
);

create policy "members can update room playback"
on public.room_playback for update to authenticated
using (exists (
  select 1 from public.room_members rm
  where rm.room_id = room_playback.room_id and rm.user_id = (select auth.uid())
))
with check (
  updated_by = (select auth.uid())
  and exists (
    select 1 from public.room_members rm
    where rm.room_id = room_playback.room_id and rm.user_id = (select auth.uid())
  )
);

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
begin
  if v_user_id is null then raise exception 'Authentication required'; end if;
  loop
    v_code := encode(extensions.gen_random_bytes(8), 'hex');
    begin
      insert into public.rooms(code_hash, created_by)
      values (encode(extensions.digest(convert_to(v_code, 'UTF8'), 'sha256'), 'hex'), v_user_id)
      returning id into v_room_id;
      exit;
    exception when unique_violation then
      -- Regenerate on the practically impossible invite-code collision.
    end;
  end loop;
  insert into public.room_members(room_id, user_id) values (v_room_id, v_user_id);
  return query select v_room_id, v_code;
end;
$$;

create or replace function public.join_room(p_code text)
returns uuid
language plpgsql
security definer
set search_path = pg_catalog, public, extensions, auth, private
as $$
declare
  v_user_id uuid := auth.uid();
  v_room_id uuid;
  v_code text := lower(btrim(coalesce(p_code, '')));
  v_member_count integer;
begin
  if v_user_id is null then raise exception 'Authentication required'; end if;
  if v_code !~ '^[0-9a-f]{16}$' then raise exception 'Invalid or expired room code'; end if;
  insert into private.room_join_attempts(user_id) values (v_user_id);
  if (
    select count(*) from private.room_join_attempts
    where user_id = v_user_id and attempted_at > now() - interval '10 minutes'
  ) > 10 then
    raise exception 'Too many attempts. Wait a few minutes and try again.';
  end if;
  select r.id into v_room_id
  from public.rooms r
  where r.code_hash = encode(extensions.digest(convert_to(v_code, 'UTF8'), 'sha256'), 'hex')
    and r.last_activity_at > now() - interval '1 hour'
  for update;
  if v_room_id is null then raise exception 'Invalid or expired room code'; end if;
  if exists (
    select 1 from public.room_members where room_id = v_room_id and user_id = v_user_id
  ) then
    update public.rooms set last_activity_at = now() where id = v_room_id;
    update public.room_members set last_seen_at = now()
      where room_id = v_room_id and user_id = v_user_id;
    return v_room_id;
  end if;
  select count(*) into v_member_count from public.room_members where room_id = v_room_id;
  if v_member_count >= 2 then raise exception 'This room already has two members'; end if;
  insert into public.room_members(room_id, user_id) values (v_room_id, v_user_id);
  update public.rooms set last_activity_at = now() where id = v_room_id;
  return v_room_id;
end;
$$;

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
  update public.room_members set last_seen_at = now()
  where room_id = p_room_id and user_id = v_user_id;
  if not found then return false; end if;
  update public.rooms set last_activity_at = now() where id = p_room_id;
  return true;
end;
$$;

create or replace function public.update_room_activity_from_child()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  update public.rooms set last_activity_at = now()
  where id = coalesce(new.room_id, old.room_id);
  return coalesce(new, old);
end;
$$;
create trigger room_messages_touch_activity after insert on public.room_messages
for each row execute function public.update_room_activity_from_child();
create trigger room_playback_touch_activity after insert or update on public.room_playback
for each row execute function public.update_room_activity_from_child();

create or replace function public.cleanup_inactive_rooms()
returns integer
language plpgsql
security definer
set search_path = pg_catalog, public, private
as $$
declare v_deleted integer;
begin
  delete from public.rooms where last_activity_at < now() - interval '1 hour';
  get diagnostics v_deleted = row_count;
  delete from private.room_join_attempts where attempted_at < now() - interval '1 hour';
  return v_deleted;
end;
$$;

revoke all on function public.create_room() from public, anon;
revoke all on function public.join_room(text) from public, anon;
revoke all on function public.touch_room(uuid) from public, anon;
revoke all on function public.cleanup_inactive_rooms() from public, anon, authenticated;
grant execute on function public.create_room() to authenticated;
grant execute on function public.join_room(text) to authenticated;
grant execute on function public.touch_room(uuid) to authenticated;

create extension if not exists pg_cron with schema pg_catalog;
do $$
begin
  if exists (select 1 from cron.job where jobname = 'nexus-expire-inactive-rooms') then
    perform cron.unschedule('nexus-expire-inactive-rooms');
  end if;
  perform cron.schedule('nexus-expire-inactive-rooms', '*/5 * * * *', 'select public.cleanup_inactive_rooms();');
end;
$$;

do $$
begin
  if not exists (select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='room_messages') then
    alter publication supabase_realtime add table public.room_messages;
  end if;
  if not exists (select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='room_playback') then
    alter publication supabase_realtime add table public.room_playback;
  end if;
  if not exists (select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='room_members') then
    alter publication supabase_realtime add table public.room_members;
  end if;
end;
$$;
