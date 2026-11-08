-- Persist each room chat message and broadcast its saved row on the room's
-- membership-authorized private Realtime topic.
create or replace function public.send_room_message(
  p_room_id uuid,
  p_body text
)
returns public.room_messages
language plpgsql
security definer
set search_path = pg_catalog
as $function$
declare
  v_user_id uuid := auth.uid();
  v_body text := btrim(p_body);
  v_message public.room_messages;
begin
  if v_user_id is null then
    raise exception 'Authentication required';
  end if;
  if p_room_id is null then
    raise exception 'Room ID is required';
  end if;
  if v_body is null or char_length(v_body) < 1 or char_length(v_body) > 2000 then
    raise exception 'Messages must contain 1-2000 characters';
  end if;

  if not exists (
    select 1
    from public.room_members rm
    join public.rooms r on r.id = rm.room_id
    where rm.room_id = p_room_id
      and rm.user_id = v_user_id
      and r.last_activity_at > pg_catalog.now() - interval '1 hour'
  ) then
    raise exception 'Room membership is missing or expired';
  end if;

  insert into public.room_messages (room_id, sender_id, body)
  values (p_room_id, v_user_id, v_body)
  returning * into v_message;

  perform realtime.send(
    pg_catalog.jsonb_build_object(
      'id', v_message.id,
      'sender_id', v_message.sender_id,
      'body', v_message.body,
      'created_at', v_message.created_at,
      'room_id', v_message.room_id
    ),
    'chat',
    'nexus-room-' || p_room_id::text,
    true
  );

  return v_message;
end;
$function$;

revoke all on function public.send_room_message(uuid, text) from public, anon;
grant execute on function public.send_room_message(uuid, text) to authenticated;
