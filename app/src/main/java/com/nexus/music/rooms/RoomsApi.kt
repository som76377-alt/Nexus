package com.nexus.music.rooms

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nexus.music.BuildConfig
import kotlinx.coroutines.Dispatchers
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
    val updatedAt: String
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val apiKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY

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
        val normalized = code.trim().lowercase()
        if (!normalized.matches(Regex("^[0-9a-f]{16}$"))) {
            throw IOException("Enter the 16-character room code.")
        }
        val result = apiRequest(
            "POST",
            "/rest/v1/rpc/join_room",
            JSONObject().put("p_code", normalized).toString()
        ).trim()
        result.removeSurrounding("\"")
    }

    suspend fun touchRoom(roomId: String): Boolean = withContext(Dispatchers.IO) {
        apiRequest(
            "POST",
            "/rest/v1/rpc/touch_room",
            JSONObject().put("p_room_id", roomId).toString()
        ).trim().equals("true", ignoreCase = true)
    }

    suspend fun loadMessages(roomId: String): List<RoomMessage> = withContext(Dispatchers.IO) {
        val rows = JSONArray(
            apiRequest(
                "GET",
                "/rest/v1/room_messages?select=id,sender_id,body,created_at" +
                    "&room_id=eq.$roomId&order=created_at.asc&limit=100",
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
        }
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
                    "position_ms,duration_ms,revision,updated_by,updated_at" +
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
            updatedAt = row.optString("updated_at")
        )
    }

    suspend fun publishPlayback(
        roomId: String,
        userId: String,
        sourceId: String,
        sourceUrl: String,
        title: String,
        artist: String,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("room_id", roomId)
            .put("source_id", sourceId)
            .put("source_url", sourceUrl)
            .put("title", title)
            .put("artist", artist)
            .put("is_playing", isPlaying)
            .put("position_ms", positionMs.coerceAtLeast(0L))
            .put("duration_ms", durationMs.coerceAtLeast(0L))
            .put("updated_by", userId)
        apiRequest(
            "POST",
            "/rest/v1/room_playback?on_conflict=room_id",
            payload.toString(),
            prefer = "resolution=merge-duplicates,return=minimal"
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

        val refreshed = refreshSession(session)
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

    private fun ensureSession(): RoomSession {
        val saved = sessionStore.read()
        if (saved != null && saved.expiresAtEpochSeconds > System.currentTimeMillis() / 1000L + 90L) {
            return saved
        }
        if (saved != null && saved.refreshToken.isNotBlank()) {
            runCatching { return refreshSession(saved) }
        }
        val response = authRequest("/auth/v1/signup", "{}")
        val userId = JSONObject(response).optJSONObject("user")?.optString("id").orEmpty()
        val session = parseSession(response, userId)
        sessionStore.write(session)
        return session
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

    fun read(): RoomSession? = runCatching {
        val encoded = preferences.getString("session", null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        if (packed.size <= 12) return null
        val iv = packed.copyOfRange(0, 12)
        val encrypted = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        RoomSession(
            json.getString("user_id"),
            json.getString("access_token"),
            json.getString("refresh_token"),
            json.getLong("expires_at")
        )
    }.getOrNull()

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
