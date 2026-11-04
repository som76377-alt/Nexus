package com.nexus.music.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomPlaybackMathTest {
    @Test
    fun remotePlayingPositionAdvancesByServerElapsedTime() {
        assertEquals(
            6_500L,
            RoomPlaybackMath.expectedRemotePositionMs(
                positionMs = 4_000L,
                isPlaying = true,
                elapsedSinceUpdateMs = 2_500L,
                durationMs = 20_000L
            )
        )
    }

    @Test
    fun pausedRemotePositionDoesNotAdvance() {
        assertEquals(
            4_000L,
            RoomPlaybackMath.expectedRemotePositionMs(
                positionMs = 4_000L,
                isPlaying = false,
                elapsedSinceUpdateMs = 90_000L,
                durationMs = 20_000L
            )
        )
    }

    @Test
    fun futureServerTimestampDoesNotMovePositionBackwards() {
        assertEquals(
            4_000L,
            RoomPlaybackMath.expectedRemotePositionMs(
                positionMs = 4_000L,
                isPlaying = true,
                elapsedSinceUpdateMs = -500L,
                durationMs = 20_000L
            )
        )
    }

    @Test
    fun remotePositionIsCappedAtKnownDuration() {
        assertEquals(
            9_000L,
            RoomPlaybackMath.expectedRemotePositionMs(
                positionMs = 8_000L,
                isPlaying = true,
                elapsedSinceUpdateMs = 5_000L,
                durationMs = 9_000L
            )
        )
    }

    @Test
    fun localNaturalProgressionDoesNotLookLikeASeek() {
        val expected = RoomPlaybackMath.expectedLocalPositionMs(
            positionMs = 10_000L,
            isPlaying = true,
            lastPublishedElapsedMs = 5_000L,
            nowElapsedMs = 6_800L
        )
        assertEquals(11_800L, expected)
        assertFalse(RoomPlaybackMath.shouldPublishPosition(11_850L, expected))
    }

    @Test
    fun userSeekTriggersPositionPublish() {
        val expected = RoomPlaybackMath.expectedLocalPositionMs(
            positionMs = 10_000L,
            isPlaying = true,
            lastPublishedElapsedMs = 5_000L,
            nowElapsedMs = 6_800L
        )
        assertTrue(RoomPlaybackMath.shouldPublishPosition(15_000L, expected))
    }

    @Test
    fun remoteSeekUsesDriftThreshold() {
        assertFalse(RoomPlaybackMath.needsSeek(10_000L, 11_000L))
        assertTrue(RoomPlaybackMath.needsSeek(10_000L, 12_000L))
    }

    @Test
    fun arithmeticSaturatesInsteadOfOverflowing() {
        assertEquals(
            Long.MAX_VALUE,
            RoomPlaybackMath.expectedRemotePositionMs(
                positionMs = Long.MAX_VALUE - 10L,
                isPlaying = true,
                elapsedSinceUpdateMs = 100L,
                durationMs = 0L
            )
        )
    }
}
