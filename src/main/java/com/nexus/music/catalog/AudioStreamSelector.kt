package com.nexus.music.catalog

import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod

/**
 * Selects an HTTPS audio stream that Media3 can address directly.
 * Manifest payloads (isUrl == false) are not treated as ordinary media URLs.
 */
object AudioStreamSelector {
    fun bestPlayable(streams: List<AudioStream>): AudioStream? =
        streams.asSequence()
            .filter { it.isUrl() && CatalogUrlValidator.isValidHttpsUrl(it.content) }
            // Bitrate is the primary quality signal. Prefer progressive HTTP only as a tie-breaker.
            .maxWithOrNull(
                compareBy<AudioStream> { it.averageBitrate }
                    .thenBy { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            )
}
