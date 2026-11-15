package com.nexus.music

import androidx.media3.common.Player
import com.nexus.music.playback.PlaybackSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerVisibilityTest {
    @Test
    fun hiddenWhenNoMediaItemIsLoaded() {
        assertFalse(shouldShowMiniPlayer(PlaybackSnapshot(connected = true)))
        assertFalse(shouldShowMiniPlayer(PlaybackSnapshot(connected = false)))
    }

    @Test
    fun visibleWhileBufferingOrPlaying() {
        val buffering = PlaybackSnapshot(
            connected = true,
            sourceId = "https://youtube.com/watch?v=abc",
            playbackState = Player.STATE_BUFFERING
        )
        val playing = buffering.copy(
            playbackState = Player.STATE_READY,
            isPlaying = true,
            playWhenReady = true
        )

        assertTrue(shouldShowMiniPlayer(buffering))
        assertTrue(shouldShowMiniPlayer(playing))
    }

    @Test
    fun remainsVisibleWhenPaused() {
        val paused = PlaybackSnapshot(
            connected = true,
            sourceId = "https://youtube.com/watch?v=abc",
            playbackState = Player.STATE_READY,
            isPlaying = false,
            playWhenReady = false
        )

        assertTrue(shouldShowMiniPlayer(paused))
    }

    @Test
    fun hiddenWhenPlaybackHasEndedOrReturnedToIdle() {
        val loaded = PlaybackSnapshot(
            connected = true,
            sourceId = "https://youtube.com/watch?v=abc",
            playbackState = Player.STATE_READY
        )

        assertFalse(shouldShowMiniPlayer(loaded.copy(playbackState = Player.STATE_ENDED)))
        assertFalse(shouldShowMiniPlayer(loaded.copy(playbackState = Player.STATE_IDLE)))
    }
}
