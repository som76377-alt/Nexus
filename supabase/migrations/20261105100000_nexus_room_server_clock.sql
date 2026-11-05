-- Expose a minimal authenticated database clock endpoint for room sync calibration.
create or replace function public.room_server_time()
returns timestamptz
language sql
volatile
security invoker
set search_path = pg_catalog
as $$
  select clock_timestamp();
$$;

revoke all on function public.room_server_time() from public, anon;
grant execute on function public.room_server_time() to authenticated;
