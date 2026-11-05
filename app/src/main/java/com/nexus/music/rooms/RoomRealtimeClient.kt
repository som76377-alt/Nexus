package com.nexus.music.rooms

import com.nexus.music.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Authenticated Supabase Realtime Postgres Changes subscription for one room.
 *
 * The database row remains authoritative. A Realtime event is only a fast signal to
 * re-read that row; it is never applied as an unverified command. The caller retains
 * a REST polling fallback for missed events and reconnect gaps.
 */
internal class RoomRealtimeClient(
    private val api: RoomsApi,
    private val roomId: String,
    private val scope: CoroutineScope,
    private val onPlaybackSignal: () -> Unit,
    private val onChatSignal: () -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var job: Job? = null
    @Volatile private var activeSocket: WebSocket? = null
    @Volatile private var lastSuccessfulJoinElapsedMs: Long = 0L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var retryDelayMs = 1_000L
            while (currentCoroutineContext().isActive) {
                try {
                    val token = api.realtimeAccessToken()
                    runConnection(token)
                    retryDelayMs = 1_000L
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    onConnectionChanged(false)
                }
                if (currentCoroutineContext().isActive) {
                    val wasStable = lastSuccessfulJoinElapsedMs > 0L &&
                        android.os.SystemClock.elapsedRealtime() - lastSuccessfulJoinElapsedMs >= 30_000L
                    if (wasStable) retryDelayMs = 1_000L
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2L).coerceAtMost(20_000L)
                }
            }
        }
    }

    private suspend fun runConnection(accessToken: String) {
        val joined = CompletableDeferred<Boolean>()
        val ended = CompletableDeferred<Throwable>()
        val topic = "realtime:nexus-room-$roomId"
        val joinRef = "1"
        val endpoint = BuildConfig.SUPABASE_URL.toHttpUrl().newBuilder()
            .scheme("wss")
            .addPathSegments("realtime/v1/websocket")
            .addQueryParameter("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            .addQueryParameter("vsn", "1.0.0")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val filter = JSONObject()
                    .put("event", "*")
                    .put("schema", "public")
                    .put("table", "room_playback")
                    .put("filter", "room_id=eq.$roomId")
                val chatFilter = JSONObject()
                    .put("event", "INSERT")
                    .put("schema", "public")
                    .put("table", "room_messages")
                    .put("filter", "room_id=eq.$roomId")
                val config = JSONObject()
                    .put("broadcast", JSONObject().put("ack", false).put("self", false))
                    .put("presence", JSONObject().put("enabled", false))
                    .put("postgres_changes", JSONArray().put(filter).put(chatFilter))
                    .put("private", false)
                val payload = JSONObject()
                    .put("config", config)
                    .put("access_token", accessToken)
                val message = JSONObject()
                    .put("topic", topic)
                    .put("event", "phx_join")
                    .put("payload", payload)
                    .put("ref", joinRef)
                    .put("join_ref", joinRef)
                if (!webSocket.send(message.toString())) {
                    ended.complete(IOException("Realtime join could not be sent."))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }.getOrNull()?.let { message ->
                    when (message.optString("event")) {
                        "phx_reply" -> {
                            val isJoinReply = message.optString("ref") == joinRef &&
                                (message.optString("join_ref").isBlank() ||
                                    message.optString("join_ref") == joinRef)
                            if (isJoinReply) {
                                val payload = message.optJSONObject("payload")
                                if (payload?.optString("status") == "ok") {
                                    joined.complete(true)
                                } else {
                                    val detail = payload?.optJSONObject("response")
                                        ?.optString("reason").orEmpty()
                                    joined.complete(false)
                                    ended.complete(IOException(detail.ifBlank {
                                        "Realtime subscription was rejected."
                                    }))
                                }
                            }
                        }
                        "postgres_changes" -> {
                            val data = message.optJSONObject("payload")
                                ?.optJSONObject("data")
                            when (data?.optString("table")) {
                                "room_playback" -> onPlaybackSignal()
                                "room_messages" -> onChatSignal()
                            }
                        }
                        "system" -> {
                            val payload = message.optJSONObject("payload")
                            if (payload?.optString("status") == "error") {
                                val detail = payload.optString("message")
                                    .ifBlank { "Realtime could not deliver a database change." }
                                ended.complete(IOException(detail))
                            }
                        }
                        "phx_error", "phx_close" -> ended.complete(
                            IOException("Realtime channel closed.")
                        )
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                joined.complete(false)
                ended.complete(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ended.complete(IOException("Realtime connection closed ($code)."))
            }
        }

        val request = Request.Builder().url(endpoint).build()
        val socket = client.newWebSocket(request, listener)
        activeSocket = socket
        try {
            val accepted = withTimeoutOrNull(12_000L) { joined.await() } == true
            if (!accepted) throw IOException("Realtime subscription did not become ready.")
            lastSuccessfulJoinElapsedMs = android.os.SystemClock.elapsedRealtime()
            onConnectionChanged(true)

            var heartbeatRef = 2
            while (currentCoroutineContext().isActive) {
                val endError = withTimeoutOrNull(20_000L) { ended.await() }
                if (endError != null) throw endError
                val heartbeat = JSONObject()
                    .put("topic", "phoenix")
                    .put("event", "heartbeat")
                    .put("payload", JSONObject())
                    .put("ref", (heartbeatRef++).toString())
                if (!socket.send(heartbeat.toString())) {
                    throw IOException("Realtime heartbeat could not be sent.")
                }
            }
        } finally {
            onConnectionChanged(false)
            if (activeSocket === socket) activeSocket = null
            socket.close(1000, "Room screen closed")
        }
    }

    fun close() {
        job?.cancel()
        job = null
        activeSocket?.close(1000, "Room screen closed")
        activeSocket = null
        onConnectionChanged(false)
    }
}
