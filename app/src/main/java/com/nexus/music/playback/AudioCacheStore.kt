package com.nexus.music.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
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
    const val DEFAULT_MAX_BYTES = 1L shl 30 // 1 GiB

    @Volatile
    private var instance: SimpleCache? = null

    fun cache(context: Context): Cache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(context.applicationContext.filesDir, "audio-cache"),
            LeastRecentlyUsedCacheEvictor(DEFAULT_MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext)
        ).also { instance = it }
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
