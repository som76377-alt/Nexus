package com.nexus.music.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.ContentMetadata
import com.nexus.music.catalog.ResolvedAudio
import com.nexus.music.catalog.StreamProvider
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Persistent stream metadata; request headers and cookies are deliberately never stored. */
@OptIn(UnstableApi::class)
internal class PersistentAudioCacheLookup(context: Context) : AudioCacheLookup {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("nexus_audio_cache_metadata", Context.MODE_PRIVATE)

    override suspend fun fullyCached(sourceUrl: String): ResolvedAudio? = withContext(Dispatchers.IO) {
        val entry = read(sourceUrl) ?: return@withContext null
        val key = "${entry.videoId}:${entry.itag}"
        val cache = AudioCacheStore.cache(app)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        val cached = if (length > 0L) cache.getCachedLength(key, 0L, length) else -1L
        if (AudioCachePolicy.isFullyCached(length, cached)) entry.toAudio() else null
    }

    override suspend fun freshUrl(sourceUrl: String, nowEpochMs: Long): ResolvedAudio? =
        withContext(Dispatchers.IO) {
            read(sourceUrl)?.takeIf { AudioCachePolicy.isUrlFresh(it.expiryEpochMs, nowEpochMs) }?.toAudio()
        }

    override suspend fun remember(audio: ResolvedAudio, nowEpochMs: Long) =
        rememberImmediately(audio, nowEpochMs)

    fun rememberImmediately(audio: ResolvedAudio, nowEpochMs: Long) {
        val videoId = AudioCachePolicy.videoId(audio.sourceUrl) ?: return
        val itag = AudioCachePolicy.itag(audio.audioUrl) ?: return
        val json = JSONObject().apply {
            put("itag", itag); put("title", audio.title); put("artist", audio.artist)
            put("thumbnailUrl", audio.thumbnailUrl); put("mimeType", audio.mimeType)
            put("durationMs", (audio.durationSeconds ?: 0L) * 1_000L)
            put("averageBitrate", audio.averageBitrate); put("audioUrl", audio.audioUrl)
            put("expiryEpochMs", AudioCachePolicy.expiryEpochMs(audio.audioUrl, nowEpochMs))
            put("provider", audio.provider.name); put("cachedAtMs", nowEpochMs)
        }
        prefs.edit().putString(videoId, json.toString()).apply()
    }

    override suspend fun evictFailed(sourceUrl: String, audioUrl: String) = withContext(Dispatchers.IO) {
        val videoId = AudioCachePolicy.videoId(sourceUrl) ?: return@withContext
        val itag = AudioCachePolicy.itag(audioUrl) ?: return@withContext
        AudioCacheStore.evict(sourceUrl, audioUrl, app)
        if (read(sourceUrl)?.itag == itag) prefs.edit().remove(videoId).apply()
    }

    override fun clear() {
        AudioCacheStore.clear(app)
        prefs.edit().clear().apply()
    }

    private fun read(sourceUrl: String): Entry? {
        val videoId = AudioCachePolicy.videoId(sourceUrl) ?: return null
        val raw = prefs.getString(videoId, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            Entry(videoId, json.getString("itag"), json.optString("title").ifBlank { "Unknown track" },
                json.optString("artist").takeIf(String::isNotBlank),
                json.optString("thumbnailUrl").takeIf(String::isNotBlank),
                json.optString("mimeType").takeIf(String::isNotBlank),
                json.optLong("durationMs").coerceAtLeast(0L),
                json.optInt("averageBitrate").takeIf { it > 0 },
                json.getString("audioUrl"), json.optLong("expiryEpochMs"),
                runCatching { StreamProvider.valueOf(json.optString("provider")) }.getOrDefault(StreamProvider.NEWPIPE))
        }.getOrNull()
    }

    private data class Entry(
        val videoId: String, val itag: String, val title: String, val artist: String?,
        val thumbnailUrl: String?, val mimeType: String?, val durationMs: Long,
        val averageBitrate: Int?, val audioUrl: String, val expiryEpochMs: Long, val provider: StreamProvider
    ) {
        fun toAudio() = ResolvedAudio(
            sourceUrl = "https://www.youtube.com/watch?v=$videoId", title = title, artist = artist,
            thumbnailUrl = thumbnailUrl, durationSeconds = durationMs / 1_000L, audioUrl = audioUrl,
            averageBitrate = averageBitrate, mimeType = mimeType, requestHeaders = emptyMap(), provider = provider
        )
    }
}
