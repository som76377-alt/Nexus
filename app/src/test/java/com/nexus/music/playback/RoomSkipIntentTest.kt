package com.nexus.music.playback

import com.nexus.music.rooms.domain.RoomPlayerIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomSkipIntentTest {

    @Test
    fun changedTrackBecomesSelectTrackAndPreservesPlayIntent() {
        assertEquals(
            RoomPlayerIntent.SelectTrack(
                sourceId = "https://www.youtube.com/watch?v=next",
                canonicalSourceUrl = "https://www.youtube.com/watch?v=next",
                title = "Next track",
                artist = "Artist",
                shouldPlay = false
            ),
            resolveSkipIntent(
                previousSourceId = "https://www.youtube.com/watch?v=current",
                previousPositionMs = 12_000L,
                shouldPlay = false,
                currentSourceId = "https://www.youtube.com/watch?v=next",
                currentTitle = "Next track",
                currentArtist = "Artist",
                currentPositionMs = 0L
            )
        )
    }

    @Test
    fun unchangedTrackBecomesSeekOnlyWhenPositionChanged() {
        assertEquals(
            RoomPlayerIntent.SeekTo(24_000L),
            resolveSkipIntent(
                previousSourceId = "track",
                previousPositionMs = 12_000L,
                shouldPlay = true,
                currentSourceId = "track",
                currentTitle = "Track",
                currentArtist = null,
                currentPositionMs = 24_000L
            )
        )
        assertNull(
            resolveSkipIntent(
                previousSourceId = "track",
                previousPositionMs = 12_000L,
                shouldPlay = true,
                currentSourceId = "track",
                currentTitle = "Track",
                currentArtist = null,
                currentPositionMs = 12_000L
            )
        )
    }

    @Test
    fun changedTrackWithoutPublishableMetadataDoesNotCreateRoomIntent() {
        assertNull(
            resolveSkipIntent(
                previousSourceId = "current",
                previousPositionMs = 0L,
                shouldPlay = true,
                currentSourceId = "next",
                currentTitle = "",
                currentArtist = null,
                currentPositionMs = 0L
            )
        )
    }

    @Test
    fun negativeSeekPositionIsClamped() {
        assertEquals(
            RoomPlayerIntent.SeekTo(0L),
            resolveSkipIntent(
                previousSourceId = "track",
                previousPositionMs = 10L,
                shouldPlay = false,
                currentSourceId = "track",
                currentTitle = "Track",
                currentArtist = null,
                currentPositionMs = -10L
            )
        )
    }
}
