package com.nexus.music.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import java.io.File

/**
 * Persistent audio-byte cache shared by the one service-owned ExoPlayer.
 *
 * Stored in app-private files (not cacheDir) so Android's opportunistic cache cleanup
 * cannot silently remove offline tracks. android:allowBackup=false excludes it from backup.
 */
@OptIn(UnstableApi::class)
internal object AudioCacheStore {
    const val DEFAULT_MAX_BYTES = 1L shl 30
    const val MIN_MAX_BYTES = 500L shl 20
    const val MAX_MAX_BYTES = 2L shl 30
    private const val PREFS = "nexus_audio_cache"
    private const val KEY_MAX_BYTES = "max_bytes"

    private class ConfigurableLruEvictor(initialMax: Long) : CacheEvictor {
        @Volatile var maxBytes = initialMax
        override fun onCacheInitialized() = Unit
        override fun onStartFile(cache: Cache, key: String, position: Long, maxLength: Long) = evict(cache)
        override fun onSpanAdded(cache: Cache, span: CacheSpan) = evict(cache)
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) = Unit
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = evict(cache)
        override fun requiresCacheSpanTouches() = true

        @Synchronized fun evict(cache: Cache) {
            if (cache.cacheSpace <= maxBytes) return
            val spans = cache.keys.flatMap(cache::getCachedSpans)
            val byIdentity = spans.associateBy { it.key to it.position }
            val oldestFirst = AudioCachePolicy.oldestFirst(
                spans.map { AudioCachePolicy.CacheSpanOrder(it.key, it.position, it.lastTouchTimestamp) }
            )
            for (candidate in oldestFirst) {
                if (cache.cacheSpace <= maxBytes) break
                byIdentity[candidate.key to candidate.position]?.let { span ->
                    runCatching { cache.removeSpan(span) }
                }
            }
        }
    }

    @Volatile private var instance: SimpleCache? = null
    @Volatile private var activeEvictor: ConfigurableLruEvictor? = null

    fun maxBytes(context: Context): Long =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_MAX_BYTES, DEFAULT_MAX_BYTES).let(AudioCachePolicy::clampCacheBytes)

    fun usedBytes(context: Context): Long = cache(context).cacheSpace

    fun cache(context: Context): Cache = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            val evictor = ConfigurableLruEvictor(maxBytes(app))
            activeEvictor = evictor
            SimpleCache(File(app.filesDir, "audio-cache"), evictor, StandaloneDatabaseProvider(app))
                .also { instance = it }
        }
    }

    fun setMaxBytes(context: Context, bytes: Long) {
        val app = context.applicationContext
        val bounded = AudioCachePolicy.clampCacheBytes(bytes)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_MAX_BYTES, bounded).apply()
        val activeCache = cache(app)
        activeEvictor?.let { it.maxBytes = bounded; it.evict(activeCache) }
    }

    fun clear(context: Context) {
        val activeCache = cache(context)
        activeCache.keys.toList().forEach { key -> runCatching { activeCache.removeResource(key) } }
    }

    fun evict(sourceUrl: String, audioUrl: String, context: Context) {
        AudioCachePolicy.cacheKey(sourceUrl, audioUrl)?.let { key ->
            runCatching { cache(context).removeResource(key) }
        }
    }

    fun dataSourceFactory(
        context: Context,
        upstream: DataSource.Factory
    ): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}
