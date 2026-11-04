package com.nexus.music.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod

class AudioStreamSelectorTest {
    @Test
    fun choosesHighestBitrateBeforeDeliveryMethodPreference() {
        val progressiveLow = audio(
            "progressive-low",
            "https://media.example/low.m4a",
            96_000,
            DeliveryMethod.PROGRESSIVE_HTTP
        )
        val dashHigh = audio(
            "dash-high",
            "https://media.example/high.m4a",
            256_000,
            DeliveryMethod.DASH
        )

        val selected = AudioStreamSelector.bestPlayable(listOf(progressiveLow, dashHigh))

        assertEquals("dash-high", selected?.id)
    }

    @Test
    fun prefersProgressiveHttpWhenBitratesTie() {
        val hls = audio("hls", "https://media.example/audio.m3u8", 160_000, DeliveryMethod.HLS)
        val progressive = audio(
            "progressive",
            "https://media.example/audio.m4a",
            160_000,
            DeliveryMethod.PROGRESSIVE_HTTP
        )

        val selected = AudioStreamSelector.bestPlayable(listOf(hls, progressive))

        assertEquals("progressive", selected?.id)
    }

    @Test
    fun ignoresManifestPayloadsThatAreNotDirectUrls() {
        val manifest = AudioStream.Builder()
            .setId("manifest")
            .setContent("#EXTM3U", false)
            .setAverageBitrate(320_000)
            .build()

        assertNull(AudioStreamSelector.bestPlayable(listOf(manifest)))
    }

    @Test
    fun rejectsMalformedAndNonHttpUrls() {
        val malformed = audio("malformed", "https://", 320_000)
        val localFile = audio("local-file", "file:///tmp/audio.m4a", 320_000)
        val cleartextHttp = audio("cleartext-http", "http://media.example/audio.m4a", 320_000)

        assertNull(AudioStreamSelector.bestPlayable(listOf(malformed, localFile, cleartextHttp)))
    }

    private fun audio(
        id: String,
        url: String,
        bitrate: Int,
        deliveryMethod: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP
    ): AudioStream =
        AudioStream.Builder()
            .setId(id)
            .setContent(url, true)
            .setAverageBitrate(bitrate)
            .setDeliveryMethod(deliveryMethod)
            .build()
}
