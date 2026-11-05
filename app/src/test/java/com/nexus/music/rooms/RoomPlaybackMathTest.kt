package com.nexus.music.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomPlaybackMathTest {
    @Test
    fun remotePlayingPositionAdvancesByElapsedTime() {
        assertEquals(
            6_500L,
            RoomPlaybackMath.expectedRemotePositionMs(4_000L, true, 2_500L, 20_000L)
        )
    }

    @Test
    fun pausedPositionDoesNotAdvance() {
        assertEquals(
            4_000L,
            RoomPlaybackMath.expectedRemotePositionMs(4_000L, false, 90_000L, 20_000L)
        )
    }

    @Test
    fun futureTimestampDoesNotMovePositionBackwards() {
        assertEquals(
            4_000L,
            RoomPlaybackMath.expectedRemotePositionMs(4_000L, true, -500L, 20_000L)
        )
    }

    @Test
    fun projectedPositionIsCappedAtDuration() {
        assertEquals(
            9_000L,
            RoomPlaybackMath.expectedRemotePositionMs(8_000L, true, 5_000L, 9_000L)
        )
    }

    @Test
    fun deadZoneIgnoresSmallDrift() {
        val controller = DriftCorrectionController()
        repeat(5) {
            assertEquals(
                DriftCorrectionType.NONE,
                controller.evaluate(75L, false, it * 500L).type
            )
        }
    }

    @Test
    fun moderateDriftNeedsRepeatedSamplesAndUsesGentleSpeedCorrection() {
        val controller = DriftCorrectionController()
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(400L, false, 0L).type)
        val ahead = controller.evaluate(400L, false, 500L)
        assertEquals(DriftCorrectionType.SET_SPEED, ahead.type)
        assertEquals(0.98f, ahead.speed)
    }

    @Test
    fun laggingPlayerIsGentlySpedUp() {
        val controller = DriftCorrectionController()
        controller.evaluate(-350L, false, 0L)
        val behind = controller.evaluate(-350L, false, 500L)
        assertEquals(DriftCorrectionType.SET_SPEED, behind.type)
        assertEquals(1.02f, behind.speed)
    }

    @Test
    fun largeDriftSeeksOnlyAfterThreeSamplesAndRespectsCooldown() {
        val controller = DriftCorrectionController()
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_500L, false, 0L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_500L, false, 500L).type)
        assertEquals(DriftCorrectionType.SEEK, controller.evaluate(1_500L, false, 1_000L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_500L, false, 2_000L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_500L, false, 3_000L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_500L, false, 4_000L).type)
        assertEquals(DriftCorrectionType.SEEK, controller.evaluate(1_500L, false, 6_100L).type)
    }

    @Test
    fun bufferingNeverTriggersCorrection() {
        val controller = DriftCorrectionController()
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_500L, true, 0L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_500L, true, 500L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_500L, true, 1_000L).type)
    }

    @Test
    fun returningToDeadZoneResetsTemporarySpeed() {
        val controller = DriftCorrectionController()
        controller.evaluate(300L, false, 0L)
        assertEquals(DriftCorrectionType.SET_SPEED, controller.evaluate(300L, false, 500L).type)
        assertEquals(DriftCorrectionType.RESET_SPEED, controller.evaluate(50L, false, 1_000L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(50L, false, 1_500L).type)
    }

    @Test
    fun seekThresholdIsNotTriggeredByMinorDifference() {
        assertFalse(RoomPlaybackMath.needsSeek(10_000L, 10_500L))
        assertTrue(RoomPlaybackMath.needsSeek(10_000L, 11_000L))
    }
}
