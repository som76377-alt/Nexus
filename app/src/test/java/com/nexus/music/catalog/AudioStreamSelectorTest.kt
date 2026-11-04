package com.nexus.music.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.stream.AudioStream

class AudioStreamSelectorTest {
    @Test
    fun choosesHighestBitrateDirectAudioUrl() {
        val low = audio("low", "https://media.example/low.m4a", 96_000)
        val high = audio("high", "https://media.example/high.m4a", 160_000)

        val selected = AudioStreamSelector.bestPlayable(listOf(low, high))

        assertEquals("high", selected?.id)
        assertEquals(160_000, selected?.averageBitrate)
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

    private fun audio(id: String, url: String, bitrate: Int): AudioStream =
        AudioStream.Builder()
            .setId(id)
            .setContent(url, true)
            .setAverageBitrate(bitrate)
            .build()
}
