package com.nexus.music.catalog

import org.schabi.newpipe.extractor.stream.AudioStream

/**
 * Selects a directly addressable audio stream for Media3.
 * Manifest payloads (isUrl == false) are not treated as ordinary media URLs.
 */
object AudioStreamSelector {
    fun bestPlayable(streams: List<AudioStream>): AudioStream? =
        streams.asSequence()
            .filter { it.isUrl() }
            .filter {
                val url = it.content
                url.startsWith("https://") || url.startsWith("http://")
            }
            .maxWithOrNull(
                compareBy<AudioStream> {
                    if (it.deliveryMethod.name == "PROGRESSIVE_HTTP") 1 else 0
                }.thenBy { it.averageBitrate }
            )
}
