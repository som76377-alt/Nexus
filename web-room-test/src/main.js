import { createClient } from "@supabase/supabase-js";
import "./style.css";

const SUPABASE_URL = import.meta.env.VITE_SUPABASE_URL || "https://fdptukvfcpkpbutgoolj.supabase.co";
const SUPABASE_KEY = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY || "sb_publishable_jYtp6H2E2SKakHkb-Z1u4g_-ZlAai42";

const supabase = createClient(SUPABASE_URL, SUPABASE_KEY, {
  auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: false },
  realtime: { params: { eventsPerSecond: 10 } }
});

const $ = (id) => document.getElementById(id);
const state = {
  user: null, roomId: null, roomCode: null, playback: null, messages: [], activeMemberCount: 1,
  channel: null, realtimeConnected: false, pollTimer: null, heartbeatTimer: null,
  lastRefresh: null, lastEvent: "—", busy: false
};

let toastTimer;
function toast(message) {
  const node = $("toast");
  node.textContent = message;
  node.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => node.classList.remove("show"), 2600);
}
function logEvent(message) {
  const time = new Date().toLocaleTimeString();
  const list = $("event-log");
  const item = document.createElement("li");
  item.textContent = time + " · " + message;
  list.prepend(item);
  while (list.children.length > 35) list.lastElementChild.remove();
  state.lastEvent = time;
  $("last-event-value").textContent = time;
}
function setConnection(online, label) {
  $("connection-dot").classList.toggle("online", online);
  $("connection-label").textContent = label;
}
function setBusy(value) {
  state.busy = value;
  $("create-room").disabled = value;
  $("join-form").querySelector("button").disabled = value;
}
function errorMessage(error) {
  const message = error?.message || String(error || "Unknown error");
  if (/anonymous.*disabled|anonymous.*not enabled/i.test(message)) {
    return "Anonymous sign-in is disabled in this Supabase project. Nexus already uses it; check Authentication → Sign-in methods.";
  }
  return message;
}
async function ensureIdentity() {
  const { data: sessionData, error: sessionError } = await supabase.auth.getSession();
  if (sessionError) throw sessionError;
  let user = sessionData.session?.user;
  if (!user) {
    const { data, error } = await supabase.auth.signInAnonymously();
    if (error) throw error;
    user = data.user;
  }
  if (!user?.id) throw new Error("Supabase did not return a browser identity.");
  state.user = user;
  $("identity-value").textContent = user.id.slice(0, 8) + "…";
  setConnection(true, "Authenticated");
  return user;
}
function showRoom() {
  $("entry-view").classList.add("hidden");
  $("room-view").classList.remove("hidden");
}
function showEntry() {
  $("room-view").classList.add("hidden");
  $("entry-view").classList.remove("hidden");
}
function normalizeCode(value) {
  return value.trim().toUpperCase().replace(/\s+/g, "");
}
async function createRoom() {
  if (state.busy) return;
  setBusy(true);
  try {
    await ensureIdentity();
    const { data, error } = await supabase.rpc("create_room");
    if (error) throw error;
    const row = Array.isArray(data) ? data[0] : data;
    if (!row?.room_id || !row?.room_code) throw new Error("The server did not return a room code.");
    await enterRoom(row.room_id, row.room_code);
    toast("Room created. Enter its code in Nexus on your phone.");
  } catch (error) {
    toast(errorMessage(error));
    logEvent("Create room failed: " + errorMessage(error));
  } finally {
    setBusy(false);
  }
}
async function joinRoom(code) {
  if (state.busy) return;
  const normalized = normalizeCode(code);
  if (!/^(?:[A-HJ-NP-Z2-9]{5}|[0-9A-F]{16})$/.test(normalized)) {
    toast("Enter the room's five-character or legacy 16-character invite code.");
    return;
  }
  setBusy(true);
  try {
    await ensureIdentity();
    const { data, error } = await supabase.rpc("join_room", { p_code: normalized });
    if (error) throw error;
    const row = Array.isArray(data) ? data[0] : data;
    if (row?.error) throw new Error(row.error);
    if (!row?.room_id) throw new Error("The room server did not return a room ID.");
    await enterRoom(row.room_id, normalized);
    toast("Joined room.");
  } catch (error) {
    toast(errorMessage(error));
    logEvent("Join room failed: " + errorMessage(error));
  } finally {
    setBusy(false);
  }
}
async function enterRoom(roomId, roomCode) {
  state.roomId = roomId;
  state.roomCode = roomCode;
  state.activeMemberCount = 1;
  $("room-code-label").textContent = roomCode;
  $("room-status").textContent = "Connected · loading room state…";
  showRoom();
  logEvent("Entered room " + roomCode);
  try {
    await refreshAll();
  } catch (error) {
    $("room-status").textContent = "Room joined · initial refresh will retry";
    logEvent("Initial room refresh failed: " + errorMessage(error));
  }
  stopTimers();
  startRealtime();
  scheduleSafetyPoll();
  state.heartbeatTimer = setInterval(async () => {
    if (!state.roomId) return;
    const { data, error } = await supabase.rpc("touch_room", { p_room_id: state.roomId });
    if (error || data !== true) {
      logEvent("Heartbeat failed" + (error ? ": " + error.message : ""));
      $("room-status").textContent = "Membership check failed · retrying";
    } else {
      $("room-status").textContent = state.realtimeConnected ? "Connected · live updates" : "Connected · polling fallback";
    }
  }, 25000);
}
function startRealtime() {
  if (state.channel) supabase.removeChannel(state.channel);
  const roomId = state.roomId;
  state.channel = supabase.channel("nexus-web-room-" + roomId)
    .on("postgres_changes", { event: "*", schema: "public", table: "room_playback", filter: "room_id=eq." + roomId }, () => {
      logEvent("Playback row changed");
      refreshPlayback().catch((error) => logEvent("Playback refresh failed: " + errorMessage(error)));
    })
    .on("postgres_changes", { event: "INSERT", schema: "public", table: "room_messages", filter: "room_id=eq." + roomId }, () => {
      logEvent("New room message");
      refreshMessages().catch((error) => logEvent("Chat refresh failed: " + errorMessage(error)));
    })
    .subscribe((status, error) => {
      const connected = status === "SUBSCRIBED";
      state.realtimeConnected = connected;
      scheduleSafetyPoll();
      $("realtime-pill").textContent = connected ? "REALTIME LIVE" : "REALTIME " + status;
      $("realtime-pill").style.color = connected ? "var(--lime)" : "#d6b17c";
      $("fallback-value").textContent = connected ? "30s safety poll" : "5s polling fallback";
      $("room-status").textContent = connected ? "Connected · live updates" : "Connected · restoring live updates";
      if (connected) logEvent("Realtime subscription active");
      else if (status === "CHANNEL_ERROR" || status === "TIMED_OUT") logEvent("Realtime reconnecting" + (error?.message ? ": " + error.message : ""));
    });
}
function scheduleSafetyPoll() {
  if (state.pollTimer) clearTimeout(state.pollTimer);
  if (!state.roomId) return;
  state.pollTimer = setTimeout(async () => {
    try {
      await refreshAll();
    } catch (error) {
      $("room-status").textContent = "Refreshing · " + errorMessage(error);
      logEvent("Polling error: " + errorMessage(error));
    }
    scheduleSafetyPoll();
  }, state.realtimeConnected ? 30000 : 5000);
}
function stopTimers() {
  if (state.pollTimer) clearTimeout(state.pollTimer);
  if (state.heartbeatTimer) clearInterval(state.heartbeatTimer);
  state.pollTimer = null;
  state.heartbeatTimer = null;
}
async function refreshAll() {
  if (!state.roomId) return;
  await Promise.all([refreshPlayback(), refreshMessages(), refreshPresence()]);
  state.lastRefresh = new Date().toLocaleTimeString();
  $("last-refresh-value").textContent = state.lastRefresh;
}
async function refreshPlayback() {
  if (!state.roomId) return;
  const { data, error } = await supabase.from("room_playback")
    .select("source_id,source_url,title,artist,is_playing,position_ms,duration_ms,revision,updated_by,updated_at,action_id,action_type,origin_device_id,command_at")
    .eq("room_id", state.roomId).maybeSingle();
  if (error) throw error;
  state.playback = data;
  renderPlayback();
}
async function refreshPresence() {
  if (!state.roomId) return;
  const { data, error } = await supabase.rpc("get_room_active_member_count", {
    p_room_id: state.roomId
  });
  if (error) throw error;
  const count = Number(data);
  if (!Number.isInteger(count) || count < 1 || count > 2) {
    throw new Error("The room server returned an invalid active-member count.");
  }
  state.activeMemberCount = count;
  renderMembers();
}
async function refreshMessages() {
  if (!state.roomId) return;
  const { data, error } = await supabase.from("room_messages")
    .select("id,sender_id,body,created_at").eq("room_id", state.roomId)
    .order("created_at", { ascending: false }).order("id", { ascending: false }).limit(100);
  if (error) throw error;
  state.messages = (data || []).reverse();
  renderMessages();
}
function formatTime(ms) {
  const total = Math.max(0, Math.floor((Number(ms) || 0) / 1000));
  return String(Math.floor(total / 60)).padStart(2, "0") + ":" + String(total % 60).padStart(2, "0");
}
function estimatedPosition(playback) {
  if (!playback) return 0;
  let position = Number(playback.position_ms) || 0;
  if (playback.is_playing && playback.updated_at) {
    const elapsed = Math.max(0, Date.now() - new Date(playback.updated_at).getTime());
    position += elapsed;
  }
  const duration = Number(playback.duration_ms) || 0;
  return duration > 0 ? Math.min(position, duration) : position;
}
function renderPlayback() {
  const p = state.playback;
  const hasTrack = Boolean(p?.source_id);
  const duration = Number(p?.duration_ms) || 0;
  const position = estimatedPosition(p);
  $("track-title").textContent = hasTrack ? (p.title || "Shared YouTube track") : "Nothing playing yet";
  $("track-subtitle").textContent = hasTrack ? (p.artist || ("Video ID · " + p.source_id)) : "Waiting for a room playback snapshot";
  $("revision-pill").textContent = "REV " + (p?.revision ?? "—");
  $("revision-value").textContent = p?.revision ?? "—";
  $("playback-owner-value").textContent = p?.updated_by ? p.updated_by.slice(0, 8) + "…" : "—";
  $("position-label").textContent = formatTime(position);
  $("duration-label").textContent = formatTime(duration);
  $("seek-range").max = Math.max(1, duration);
  $("seek-range").value = Math.min(position, duration || position || 0);
  $("play-command").disabled = !hasTrack;
  $("pause-command").disabled = !hasTrack;
  $("seek-command").disabled = !hasTrack || !duration;
  $("play-command").textContent = p?.is_playing ? "Play again" : "Play";
  $("playback-note").textContent = hasTrack
    ? (p.is_playing ? "Server says PLAYING · browser is monitoring the shared timeline, not decoding the track." : "Server says PAUSED · use Play to send a new room command.")
    : "Send a YouTube video ID or URL to load a track on the Android phone.";
  $("send-track").disabled = !state.roomId;
}
function renderMessages() {
  const list = $("chat-list");
  list.replaceChildren();
  if (!state.messages.length) {
    const empty = document.createElement("div");
    empty.className = "empty";
    empty.textContent = "No messages yet. Say hello.";
    list.append(empty);
    return;
  }
  for (const message of state.messages) {
    const item = document.createElement("article");
    item.className = "message" + (message.sender_id === state.user?.id ? " mine" : "");
    const meta = document.createElement("div");
    meta.className = "meta";
    meta.textContent = (message.sender_id === state.user?.id ? "YOU" : "ROOM MEMBER") + " · " + new Date(message.created_at).toLocaleTimeString();
    const body = document.createElement("p");
    body.textContent = message.body;
    item.append(meta, body);
    list.append(item);
  }
  list.scrollTop = list.scrollHeight;
}
function renderMembers() {
  const list = $("members-list");
  list.replaceChildren();
  const count = state.activeMemberCount;
  $("member-count").textContent = count + (count === 1 ? " ACTIVE PARTICIPANT" : " ACTIVE PARTICIPANTS");

  const own = document.createElement("div");
  own.className = "member";
  const ownAvatar = document.createElement("div");
  ownAvatar.className = "avatar";
  ownAvatar.textContent = "W";
  const ownInfo = document.createElement("div");
  ownInfo.className = "member-info";
  const ownName = document.createElement("strong");
  ownName.textContent = "Browser participant (you)";
  const ownId = document.createElement("span");
  ownId.textContent = (state.user?.id || "").slice(0, 8) + "…";
  ownInfo.append(ownName, ownId);
  const ownStatus = document.createElement("span");
  ownStatus.className = "member-state";
  ownStatus.textContent = "ACTIVE";
  own.append(ownAvatar, ownInfo, ownStatus);
  list.append(own);

  if (count > 1) {
    const peer = document.createElement("div");
    peer.className = "member";
    const peerAvatar = document.createElement("div");
    peerAvatar.className = "avatar";
    peerAvatar.textContent = "P";
    const peerInfo = document.createElement("div");
    peerInfo.className = "member-info";
    const peerName = document.createElement("strong");
    peerName.textContent = "Other room member";
    const peerDetail = document.createElement("span");
    peerDetail.textContent = "Confirmed active by the room server";
    peerInfo.append(peerName, peerDetail);
    const peerStatus = document.createElement("span");
    peerStatus.className = "member-state";
    peerStatus.textContent = "ACTIVE";
    peer.append(peerAvatar, peerInfo, peerStatus);
    list.append(peer);
  }
}

async function sendMessage(event) {
  event?.preventDefault();
  const body = $("chat-input").value.trim();
  if (!body || !state.roomId || !state.user) return;
  $("chat-input").disabled = true;
  try {
    const { error } = await supabase.from("room_messages").insert({
      room_id: state.roomId, sender_id: state.user.id, body
    });
    if (error) throw error;
    $("chat-input").value = "";
    await refreshMessages();
    logEvent("Browser sent a chat message");
  } catch (error) {
    toast(errorMessage(error));
    logEvent("Message send failed: " + errorMessage(error));
  } finally {
    $("chat-input").disabled = false;
    $("chat-input").focus();
  }
}
function extractYoutubeId(input) {
  const value = input.trim();
  if (/^[a-zA-Z0-9_-]{11}$/.test(value)) return value;
  try {
    const url = new URL(value);
    if (url.hostname === "youtu.be" || url.hostname.endsWith(".youtu.be")) {
      return url.pathname.split("/").filter(Boolean)[0] || null;
    }
    if (url.hostname.includes("youtube.com")) {
      const v = url.searchParams.get("v");
      if (v) return v;
      const parts = url.pathname.split("/").filter(Boolean);
      const index = parts.findIndex((part) => ["shorts", "embed", "live"].includes(part));
      if (index >= 0) return parts[index + 1] || null;
    }
  } catch { /* not a URL */ }
  return null;
}
async function publishPlayback(patch, actionType) {
  if (!state.roomId || !state.user) return;
  const current = state.playback;
  if (!current && actionType !== "TRACK") {
    toast("Send a track first.");
    return;
  }
  const sourceId = patch.source_id ?? current?.source_id;
  if (!sourceId) throw new Error("A track ID is required.");
  const args = {
    p_room_id: state.roomId,
    p_expected_revision: Number(current?.revision || 0),
    p_source_id: sourceId,
    p_source_url: patch.source_url ?? current?.source_url ?? sourceId,
    p_title: patch.title ?? current?.title ?? ("YouTube · " + sourceId),
    p_artist: patch.artist ?? current?.artist ?? "",
    p_is_playing: patch.is_playing ?? current?.is_playing ?? false,
    p_position_ms: Math.max(0, Math.floor(patch.position_ms ?? estimatedPosition(current))),
    p_duration_ms: Math.max(0, Math.floor(patch.duration_ms ?? current?.duration_ms ?? 0)),
    p_action_id: crypto.randomUUID(),
    p_action_type: actionType,
    p_origin_device_id: state.user.id
  };
  const { data, error } = await supabase.rpc("publish_room_playback", args);
  if (error) throw error;
  const result = Array.isArray(data) ? data[0] : data;
  if (!result?.applied) {
    await refreshPlayback();
    logEvent("Playback command rejected by revision check; latest server state adopted");
    toast("Another participant changed playback first. Latest state loaded; retry your command.");
    return;
  }
  logEvent(actionType + " command accepted · revision " + result.revision);
  await refreshPlayback();
}
async function sendTrack() {
  const input = $("youtube-track");
  const videoId = extractYoutubeId(input.value);
  if (!videoId || !/^[a-zA-Z0-9_-]{11}$/.test(videoId)) {
    toast("Enter a valid YouTube video URL or 11-character video ID.");
    return;
  }
  $("send-track").disabled = true;
  try {
    await publishPlayback({
      source_id: "https://www.youtube.com/watch?v=" + videoId, source_url: "https://www.youtube.com/watch?v=" + videoId, title: "YouTube track · " + videoId,
      artist: "", is_playing: false, position_ms: 0, duration_ms: 0
    }, "TRACK");
    input.value = "";
    toast("Track command sent to the room. Check the Android player.");
  } catch (error) {
    toast(errorMessage(error));
    logEvent("Track command failed: " + errorMessage(error));
  } finally {
    $("send-track").disabled = false;
  }
}
async function sendPlayState(isPlaying) {
  try {
    await publishPlayback({ is_playing: isPlaying, position_ms: estimatedPosition(state.playback) }, isPlaying ? "PLAY" : "PAUSE");
    toast(isPlaying ? "Play command sent." : "Pause command sent.");
  } catch (error) {
    toast(errorMessage(error));
    logEvent("Playback command failed: " + errorMessage(error));
  }
}
async function sendSeek() {
  const target = Number($("seek-range").value);
  try {
    await publishPlayback({ position_ms: target, is_playing: state.playback?.is_playing ?? false }, "SEEK");
    toast("Seek command sent.");
  } catch (error) {
    toast(errorMessage(error));
    logEvent("Seek command failed: " + errorMessage(error));
  }
}
async function leaveRoom() {
  if (!state.roomId) return;
  const roomId = state.roomId;
  try {
    const { data, error } = await supabase.rpc("leave_room", { p_room_id: roomId });
    if (error) throw error;
    logEvent("Browser left room · server released membership: " + Boolean(data));
  } catch (error) {
    toast("Leave request failed: " + errorMessage(error) + ". Seat will expire after missed heartbeats.");
  }
  stopTimers();
  if (state.channel) await supabase.removeChannel(state.channel);
  state.channel = null;
  state.roomId = null;
  state.roomCode = null;
  state.playback = null;
  state.messages = [];
  state.activeMemberCount = 1;
  $("event-log").replaceChildren();
  $("room-code-label").textContent = "—";
  $("room-status").textContent = "Disconnected";
  $("realtime-pill").textContent = "REALTIME —";
  setConnection(true, "Authenticated · no room");
  showEntry();
}
async function boot() {
  $("create-room").addEventListener("click", createRoom);
  $("join-form").addEventListener("submit", (event) => { event.preventDefault(); joinRoom($("room-code").value); });
  $("chat-form").addEventListener("submit", sendMessage);
  $("send-track").addEventListener("click", sendTrack);
  $("play-command").addEventListener("click", () => sendPlayState(true));
  $("pause-command").addEventListener("click", () => sendPlayState(false));
  $("seek-command").addEventListener("click", sendSeek);
  $("leave-room").addEventListener("click", leaveRoom);
  $("clear-log").addEventListener("click", () => $("event-log").replaceChildren());
  $("room-code").addEventListener("input", (event) => { event.target.value = normalizeCode(event.target.value); });
  $("seek-range").addEventListener("input", (event) => {
    $("position-label").textContent = formatTime(event.target.value);
  });
  window.addEventListener("online", () => {
    setConnection(true, "Network online");
    if (state.roomId) refreshAll().catch(() => {});
    logEvent("Browser network online");
  });
  window.addEventListener("offline", () => {
    setConnection(false, "Network offline");
    logEvent("Browser network offline");
  });
  try {
    await ensureIdentity();
    $("room-status").textContent = "Browser identity ready. Create or join a room.";
    logEvent("Browser identity authenticated");
  } catch (error) {
    setConnection(false, "Authentication failed");
    $("room-status").textContent = errorMessage(error);
    toast(errorMessage(error));
  }
}
boot();
