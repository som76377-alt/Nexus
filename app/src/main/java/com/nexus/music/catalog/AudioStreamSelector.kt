package com.nexus.music.catalog

import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.net.URI

/**
 * Selects an HTTPS audio stream that Media3 can address directly.
 * Manifest payloads (isUrl == false) are not treated as ordinary media URLs.
 */
object AudioStreamSelector {
    fun bestPlayable(streams: List<AudioStream>): AudioStream? =
        streams.asSequence()
            .filter { it.isUrl() && isHttpUrl(it.content) }
            // Bitrate is the primary quality signal. Prefer progressive HTTP only as a tie-breaker.
            .maxWithOrNull(
                compareBy<AudioStream> { it.averageBitrate }
                    .thenBy { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            )

    private fun isHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }
}
