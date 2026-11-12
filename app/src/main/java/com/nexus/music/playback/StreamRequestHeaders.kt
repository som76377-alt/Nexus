package com.nexus.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.nexus.music.catalog.StreamProvider
import com.nexus.music.catalog.sanitizeStreamRequestHeaders
import java.util.concurrent.ConcurrentHashMap

/** URL-scoped headers/provider identity for the single local ExoPlayer. Never persisted or broadcast. */
@OptIn(UnstableApi::class)
internal object StreamRequestHeaders {
    private data class StreamRequest(
        val headers: Map<String, String>,
        val provider: StreamProvider
    )

    private val active = ConcurrentHashMap<String, StreamRequest>()

    fun replace(
        streamUrl: String,
        headers: Map<String, String>,
        provider: StreamProvider = StreamProvider.NEWPIPE
    ) {
        active.clear()
        val safe = sanitizeStreamRequestHeaders(headers)
        active[streamUrl] = StreamRequest(safe, provider)
    }

    internal fun headersForUrl(streamUrl: String): Map<String, String> =
        active[streamUrl]?.headers.orEmpty()

    internal fun providerForUrl(streamUrl: String): StreamProvider? =
        active[streamUrl]?.provider

    fun resolve(dataSpec: DataSpec): DataSpec {
        val headers = headersForUrl(dataSpec.uri.toString())
        if (headers.isEmpty()) return dataSpec
        return dataSpec.buildUpon().setHttpRequestHeaders(headers).build()
    }

    internal fun snapshotForTests(): Map<String, Map<String, String>> =
        active.mapNotNull { (url, request) ->
            request.headers.takeIf { it.isNotEmpty() }?.let { url to it }
        }.toMap()
}
