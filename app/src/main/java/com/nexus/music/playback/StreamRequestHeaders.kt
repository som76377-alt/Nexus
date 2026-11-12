package com.nexus.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.nexus.music.catalog.sanitizeStreamRequestHeaders
import java.util.concurrent.ConcurrentHashMap

/** URL-scoped headers for the single local ExoPlayer. Never persisted or broadcast. */
@OptIn(markerClass = UnstableApi::class)
internal object StreamRequestHeaders {
    private val active = ConcurrentHashMap<String, Map<String, String>>()

    fun replace(streamUrl: String, headers: Map<String, String>) {
        active.clear()
        val safe = sanitizeStreamRequestHeaders(headers)
        if (safe.isNotEmpty()) active[streamUrl] = safe
    }

    fun resolve(dataSpec: DataSpec): DataSpec {
        val headers = active[dataSpec.uri.toString()] ?: return dataSpec
        return dataSpec.buildUpon().setHttpRequestHeaders(headers).build()
    }

    internal fun snapshotForTests(): Map<String, Map<String, String>> = active.toMap()
}
