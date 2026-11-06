package com.nexus.music.rooms

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nexus.music.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class RoomIdentity(val userId: String)
data class CreatedRoom(val roomId: String, val inviteCode: String)
data class RoomMessage(
    val id: String,
    val senderId: String,
    val body: String,
    val createdAt: String
)
data class RoomPlayback(
    val sourceId: String,
    val sourceUrl: String,
    val title: String,
    val artist: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val revision: Long,
    val updatedBy: String,
    val updatedAt: String,
    val actionId: String = "",
    val actionType: String = "STATE",
    val originDeviceId: String? = null,
    val commandAt: String = ""
)

data class PlaybackPublishResult(val applied: Boolean, val revision: Long)
data class PlaybackReadyResult(
    val startAt: String?,
    val activeMembers: Int,
    val readyMembers: Int
)

/**
 * Small REST client for the two-person room prototype.
 *
 * Only the Supabase publishable key is embedded in the APK. User access/refresh tokens are
 * encrypted with an Android Keystore AES-GCM key and are never logged.
 */
class RoomsApi(context: Context) {
    private val appContext = context.applicationContext
    private val sessionStore = EncryptedRoomSessionStore(appContext)
    private val sessionLock = Any()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val apiKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    @Volatile private var serverClockOffsetFromElapsedMs: Long? = null
    @Volatile private var serverClockRoundTripMs: Long = 250L

    fun serverNowMillis(): Long = serverClockOffsetFromElapsedMs
        ?.let { SystemClock.elapsedRealtime() + it }
        ?: System.currentTimeMillis()

    fun serverClockRoundTripMillis(): Long = serverClockRoundTripMs

    /**
     * Estimate the Supabase clock from several request/response samples.
     *
     * A single midpoint sample is easily biased by asymmetric mobile-network latency.
     * Keep the three lowest-RTT samples and take their median offset so one slow or
     * unusually routed request cannot move the room timeline.
     */
    suspend fun calibrateServerClock() = withContext(Dispatchers.IO) {
        // Run the five tiny time probes concurrently. Sequential probes made room entry
        // wait behind slow mobile-network round trips even though only the best samples matter.
        val samples = coroutineScope {
            (0 until 5).map {
                async(Dispatchers.IO) {
                    val requestStarted = SystemClock.elapsedRealtime()
                    try {
                        val response = apiRequest("POST", "/rest/v1/rpc/room_server_time", "{}")
                            .trim()
                            .removeSurrounding(34.toChar().toString())
                        val requestFinished = SystemClock.elapsedRealtime()
                        val serverTime = parseServerTimestampMillis(response)
                            ?: throw IOException("The server returned an invalid clock timestamp.")
                        val midpoint = requestStarted + (requestFinished - requestStarted) / 2L
                        ClockOffsetSample(
                            offsetMs = serverTime - midpoint,
                            roundTripMs = (requestFinished - requestStarted).coerceAtLeast(0L)
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val estimate = ServerClockEstimator.estimate(samples)
            ?: throw IOException("Nexus could not obtain enough reliable server-clock samples.")
        serverClockOffsetFromElapsedMs = estimate.offsetMs
        serverClockRoundTripMs = estimate.medianRoundTripMs
    }

    private fun parseServerTimestampMillis(value: String): Long? {
        val match = Regex(
            """^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})$"""
        ).matchEntire(value) ?: return null
        val baseMillis = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("GMT")
            }.parse(match.groupValues[1])?.time
        }.getOrNull() ?: return null
        val fractionMillis = match.groupValues[2]
            .takeIf(String::isNotEmpty)
            ?.padEnd(3, '0')
            ?.take(3)
            ?.toLongOrNull() ?: 0L
        val zone = match.groupValues[3]
        val offsetMinutes = if (zone == "Z") {
            0
        } else {
            val sign = if (zone[0] == '-') -1 else 1
            val hours = zone.substring(1, 3).toIntOrNull() ?: return null
            val minutes = zone.substring(4, 6).toIntOrNull() ?: return null
            sign * (hours * 60 + minutes)
        }
        return baseMillis + fractionMillis - offsetMinutes * 60_000L
    }

    suspend fun realtimeAccessToken(): String = withContext(Dispatchers.IO) {
        ensureSession().accessToken
    }

    /**
     * Refresh the signed JWT during a long-lived Realtime connection. Refresh-token
     * rotation is persisted atomically with the new session before it is returned.
     */
    suspend fun refreshRealtimeAccessToken(): String = withContext(Dispatchers.IO) {
        synchronized(sessionLock) {
            val current = ensureSession()
            if (current.refreshToken.isBlank()) {
                throw IOException("The room session cannot refresh its Realtime token.")
            }
            refreshSession(current).accessToken
        }
    }

    suspend fun identity(): RoomIdentity = withContext(Dispatchers.IO) {
        RoomIdentity(ensureSession().userId)
    }

    suspend fun createRoom(): CreatedRoom = withContext(Dispatchers.IO) {
        val result = JSONArray(apiRequest("POST", "/rest/v1/rpc/create_room", "{}"))
        if (result.length() == 0) throw IOException("The server did not return a room.")
        val row = result.getJSONObject(0)
        CreatedRoom(row.getString("room_id"), row.getString("room_code"))
    }

    suspend fun joinRoom(code: String): String = withContext(Dispatchers.IO) {
        val normalized = RoomInviteCode.normalize(code)
        if (!RoomInviteCode.isValid(normalized)) {
            throw IOException("Enter the 5-character room code.")
        }
        val rows = JSONArray(
            apiRequest(
                "POST",
                "/rest/v1/rpc/join_room",
                JSONObject().put("p_code", normalized).toString()
            )
        )
        if (rows.length() == 0) throw IOException("The server did not return a room result.")
        val row = rows.getJSONObject(0)
        val serverError = row.optString("error").takeIf { it.isNotBlank() && it != "null" }
        if (serverError != null) throw IOException(serverError)
        row.optString("room_id").takeIf { it.isNotBlank() && it != "null" }
            ?: throw IOException("The server did not return a valid room ID.")
    }

    suspend fun touchRoom(roomId: String): Boolean = withContext(Dispatchers.IO) {
        apiRequest(
            "POST",
            "/rest/v1/rpc/touch_room",
            JSONObject().put("p_room_id", roomId).toString()
        ).trim().equals("true", ignoreCase = true)
    }

    suspend fun leaveRoom(roomId: String): Boolean = withContext(Dispatchers.IO) {
        apiRequest(
            "POST",
            "/rest/v1/rpc/leave_room",
            JSONObject().put("p_room_id", roomId).toString()
        ).trim().equals("true", ignoreCase = true)
    }

    suspend fun loadMessages(roomId: String): List<RoomMessage> = withContext(Dispatchers.IO) {
        val rows = JSONArray(
            apiRequest(
                "GET",
                "/rest/v1/room_messages?select=id,sender_id,body,created_at" +
                    "&room_id=eq.$roomId&order=created_at.desc,id.desc&limit=100",
                null
            )
        )
        buildList {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                add(
                    RoomMessage(
                        id = row.getString("id"),
                        senderId = row.getString("sender_id"),
                        body = row.getString("body"),
                        createdAt = row.optString("created_at")
                    )
                )
            }
        }.asReversed()
    }

    suspend fun sendMessage(roomId: String, senderId: String, body: String) =
        withContext(Dispatchers.IO) {
            val clean = body.trim()
            if (clean.isEmpty() || clean.length > 2000) {
                throw IOException("Messages must contain 1–2000 characters.")
            }
            val payload = JSONObject()
                .put("room_id", roomId)
                .put("sender_id", senderId)
                .put("body", clean)
            apiRequest(
                "POST",
                "/rest/v1/room_messages",
                payload.toString(),
                prefer = "return=minimal"
            )
        }

    suspend fun loadPlayback(roomId: String): RoomPlayback? = withContext(Dispatchers.IO) {
        val rows = JSONArray(
            apiRequest(
                "GET",
                "/rest/v1/room_playback?select=source_id,source_url,title,artist,is_playing," +
                    "position_ms,duration_ms,revision,updated_by,updated_at,action_id,action_type,origin_device_id,command_at" +
                    "&room_id=eq.$roomId&limit=1",
                null
            )
        )
        if (rows.length() == 0) return@withContext null
        val row = rows.getJSONObject(0)
        RoomPlayback(
            sourceId = row.getString("source_id"),
            sourceUrl = row.getString("source_url"),
            title = row.getString("title"),
            artist = row.optString("artist").takeIf(String::isNotBlank),
            isPlaying = row.optBoolean("is_playing"),
            positionMs = row.optLong("position_ms"),
            durationMs = row.optLong("duration_ms"),
            revision = row.optLong("revision"),
            updatedBy = row.getString("updated_by"),
            updatedAt = row.optString("updated_at"),
            actionId = row.optString("action_id"),
            actionType = row.optString("action_type", "STATE"),
            originDeviceId = row.optString("origin_device_id").takeIf(String::isNotBlank),
            commandAt = row.optString("command_at")
        )
    }

    suspend fun publishPlayback(
        roomId: String,
        expectedRevision: Long,
        sourceId: String,
        sourceUrl: String,
        title: String,
        artist: String,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        actionId: String,
        actionType: String,
        originDeviceId: String
    ): PlaybackPublishResult = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("p_room_id", roomId)
            .put("p_expected_revision", expectedRevision)
            .put("p_source_id", sourceId)
            .put("p_source_url", sourceUrl)
            .put("p_title", title)
            .put("p_artist", artist)
            .put("p_is_playing", isPlaying)
            .put("p_position_ms", positionMs.coerceAtLeast(0L))
            .put("p_duration_ms", durationMs.coerceAtLeast(0L))
            .put("p_action_id", actionId)
            .put("p_action_type", actionType)
            .put("p_origin_device_id", originDeviceId)
        val rows = JSONArray(
            apiRequest("POST", "/rest/v1/rpc/publish_room_playback", payload.toString())
        )
        if (rows.length() == 0) throw IOException("The server did not confirm the playback update.")
        val row = rows.getJSONObject(0)
        PlaybackPublishResult(
            applied = row.optBoolean("applied"),
            revision = row.optLong("revision")
        )
    }

    suspend fun markPlaybackReady(
        roomId: String,
        revision: Long,
        sourceId: String
    ): PlaybackReadyResult = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("p_room_id", roomId)
            .put("p_revision", revision)
            .put("p_source_id", sourceId)
        val rows = JSONArray(
            apiRequest("POST", "/rest/v1/rpc/mark_room_playback_ready", payload.toString())
        )
        if (rows.length() == 0) {
            throw IOException("The server did not confirm playback readiness.")
        }
        val row = rows.getJSONObject(0)
        PlaybackReadyResult(
            startAt = row.optString("start_at").takeIf(String::isNotBlank),
            activeMembers = row.optInt("active_members"),
            readyMembers = row.optInt("ready_members")
        )
    }

    private fun apiRequest(
        method: String,
        path: String,
        body: String?,
        prefer: String? = null
    ): String {
        val session = ensureSession()
        val first = executeRequest(method, path, body, session.accessToken, prefer)
        if (first.first != 401) return first.second

        val refreshed = synchronized(sessionLock) {
            val latest = sessionStore.read()
            val now = System.currentTimeMillis() / 1000L
            if (latest != null &&
                latest.accessToken != session.accessToken &&
                latest.expiresAtEpochSeconds > now + 90L
            ) {
                latest
            } else {
                refreshSession(latest ?: session)
            }
        }
        val retry = executeRequest(method, path, body, refreshed.accessToken, prefer)
        if (retry.first !in 200..299) throw apiError(retry.second, retry.first)
        return retry.second
    }

    private fun executeRequest(
        method: String,
        path: String,
        body: String?,
        accessToken: String,
        prefer: String?
    ): Pair<Int, String> {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
        if (!prefer.isNullOrBlank()) builder.header("Prefer", prefer)
        when (method) {
            "GET" -> builder.get()
            else -> builder.method(
                method,
                (body ?: "{}").toRequestBody(jsonType)
            )
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throwIfNotUnauthorized(response.code, text)
            return response.code to text
        }
    }

    private fun throwIfNotUnauthorized(code: Int, body: String) {
        if (code != 401) throw apiError(body, code)
    }

    private fun apiError(body: String, code: Int): IOException {
        val detail = runCatching {
            val json = JSONObject(body)
            json.optString("message").ifBlank {
                json.optString("msg").ifBlank { json.optString("details") }
            }
        }.getOrNull().orEmpty()
        val message = when {
            detail.contains("anonymous", ignoreCase = true) &&
                (detail.contains("disabled", true) || detail.contains("not enabled", true)) ->
                "Nexus room sign-in is not enabled yet. Enable Anonymous Sign-Ins in the Nexus Supabase project's Authentication settings, then reopen this screen."
            detail.isNotBlank() -> detail
            code == 401 -> "Your room session expired. Reopen the room and try again."
            else -> "Room server error ($code). Please try again."
        }
        return IOException(message)
    }

    private fun ensureSession(): RoomSession = synchronized(sessionLock) {
        val saved = sessionStore.read()
        if (saved != null) {
            if (saved.expiresAtEpochSeconds > System.currentTimeMillis() / 1000L + 90L) {
                return@synchronized saved
            }
            if (saved.refreshToken.isBlank()) {
                throw IOException("The saved room identity has expired and cannot be refreshed. It was not replaced with a new identity.")
            }
            // Never silently create a new anonymous identity when an old one fails to refresh:
            // that would strand this device's existing room memberships.
            return@synchronized refreshSession(saved)
        }
        val response = authRequest(
            "/auth/v1/signup",
            JSONObject().put("data", JSONObject()).toString()
        )
        val userId = JSONObject(response).optJSONObject("user")?.optString("id").orEmpty()
        val session = parseSession(response, userId)
        sessionStore.write(session)
        session
    }

    private fun refreshSession(old: RoomSession): RoomSession {
        val response = authRequest(
            "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", old.refreshToken).toString()
        )
        val refreshed = parseSession(response, old.userId)
        sessionStore.write(refreshed)
        return refreshed
    }

    private fun authRequest(path: String, body: String): String {
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("apikey", apiKey)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiError(text, response.code)
            return text
        }
    }

    private fun parseSession(raw: String, fallbackUserId: String): RoomSession {
        val json = JSONObject(raw)
        val userId = json.optJSONObject("user")?.optString("id")
            ?.takeIf(String::isNotBlank) ?: fallbackUserId
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        if (userId.isBlank() || access.isBlank() || refresh.isBlank()) {
            throw IOException("Supabase did not return a complete room session.")
        }
        val expiry = json.optLong("expires_at").takeIf { it > 0L }
            ?: (System.currentTimeMillis() / 1000L + json.optLong("expires_in", 3600L))
        return RoomSession(userId, access, refresh, expiry)
    }
}

private data class RoomSession(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long
)

/** Encrypts the refresh token and access token at rest using a non-exportable Keystore key. */
private class EncryptedRoomSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("nexus_room_session", Context.MODE_PRIVATE)
    private val alias = "nexus_room_session_aes_gcm"

    fun read(): RoomSession? {
        val encoded = preferences.getString("session", null) ?: return null
        try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= 12) throw IOException("Saved room session data is incomplete.")
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            return RoomSession(
                json.getString("user_id"),
                json.getString("access_token"),
                json.getString("refresh_token"),
                json.getLong("expires_at")
            )
        } catch (error: Exception) {
            throw IOException(
                "Nexus could not unlock this device's saved room identity. It has not created a replacement identity.",
                error
            )
        }
    }

    fun write(session: RoomSession) {
        val json = JSONObject()
            .put("user_id", session.userId)
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("expires_at", session.expiresAtEpochSeconds)
            .toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        preferences.edit().putString("session", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
