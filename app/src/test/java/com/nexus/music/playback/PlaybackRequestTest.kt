package com.nexus.music.playback

import com.nexus.music.catalog.ResolvedAudio
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRequestTest {
    @Test
    fun mapsResolvedStreamAndCanonicalIdentityIntoPlaybackRequest() {
        val audio = ResolvedAudio(
            sourceUrl = "https://www.youtube.com/watch?v=example",
            title = "Example track",
            artist = "Example artist",
            thumbnailUrl = "https://img.example/thumb.jpg",
            durationSeconds = 210L,
            audioUrl = "https://media.example/stream?signature=temporary",
            averageBitrate = 160_000,
            mimeType = "audio/mp4"
        )

        val request = PlaybackRequest.from(audio)

        assertEquals(audio.sourceUrl, request.sourceId)
        assertEquals(audio.audioUrl, request.streamUrl)
        assertEquals(audio.title, request.title)
        assertEquals(audio.artist, request.artist)
        assertEquals(audio.thumbnailUrl, request.thumbnailUrl)
        assertEquals(audio.mimeType, request.mimeType)
    }
}
