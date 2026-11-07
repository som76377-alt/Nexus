-- A readiness lease must not outlive the bounded start window for its playback revision.
-- Otherwise a late peer could create a START after another device has already fallen back.

create or replace function private.cap_room_playback_readiness_deadline()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    v_command_at timestamptz;
begin
    select playback.command_at
    into v_command_at
    from public.room_playback as playback
    where playback.room_id = new.room_id
      and playback.revision = new.revision;

    if v_command_at is not null then
        new.expires_at := least(
            new.expires_at,
            v_command_at + interval '4 seconds'
        );
    end if;

    return new;
end;
$$;

revoke all on function private.cap_room_playback_readiness_deadline()
    from public, anon, authenticated;

drop trigger if exists room_playback_readiness_deadline
    on private.room_playback_readiness;

create trigger room_playback_readiness_deadline
before insert or update on private.room_playback_readiness
for each row
execute function private.cap_room_playback_readiness_deadline();

-- Clamp leases that were created before this forward-only deadline was installed.
update private.room_playback_readiness as readiness
set expires_at = least(
    readiness.expires_at,
    playback.command_at + interval '4 seconds'
)
from public.room_playback as playback
where playback.room_id = readiness.room_id
  and playback.revision = readiness.revision;
