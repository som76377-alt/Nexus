-- Deliver the authenticated room playback row in the private Realtime broadcast.
-- Receivers validate room identity and monotonically increasing revision before apply.
create or replace function private.broadcast_room_playback_change()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog
as $function$
begin
  perform realtime.send(
    jsonb_build_object(
      'room_id', new.room_id,
      'source_id', new.source_id,
      'source_url', new.source_url,
      'title', new.title,
      'artist', new.artist,
      'is_playing', new.is_playing,
      'position_ms', new.position_ms,
      'duration_ms', new.duration_ms,
      'revision', new.revision,
      'updated_by', new.updated_by,
      'updated_at', new.updated_at,
      'action_id', new.action_id,
      'action_type', new.action_type,
      'origin_device_id', new.origin_device_id,
      'command_at', new.command_at
    ),
    'playback_invalidated',
    'nexus-room-' || new.room_id::text,
    true
  );
  return new;
end;
$function$;

revoke all on function private.broadcast_room_playback_change() from public, anon, authenticated;
