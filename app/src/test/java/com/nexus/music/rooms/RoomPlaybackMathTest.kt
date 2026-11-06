package com.nexus.music.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomPlaybackMathTest {
    @Test
    fun serverClockEstimatorUsesMedianOfThreeFastestSamples() {
        val estimate = ServerClockEstimator.estimateOffset(
            listOf(
                ClockOffsetSample(offsetMs = 1_000L, roundTripMs = 20L),
                ClockOffsetSample(offsetMs = 1_040L, roundTripMs = 30L),
                ClockOffsetSample(offsetMs = 1_020L, roundTripMs = 25L),
                ClockOffsetSample(offsetMs = 9_000L, roundTripMs = 900L),
                ClockOffsetSample(offsetMs = -8_000L, roundTripMs = 1_200L)
            )
        )
        assertEquals(1_020L, estimate)
    }

    @Test
    fun serverClockEstimatorRejectsInsufficientSamples() {
        assertEquals(
            null,
            ServerClockEstimator.estimateOffset(
                listOf(
                    ClockOffsetSample(offsetMs = 1_000L, roundTripMs = 20L),
                    ClockOffsetSample(offsetMs = 1_020L, roundTripMs = 30L)
                )
            )
        )
    }

    @Test
    fun serverClockEstimatorReportsMedianRttOfThreeFastestSamples() {
        val estimate = ServerClockEstimator.estimate(
            listOf(
                ClockOffsetSample(offsetMs = 900L, roundTripMs = 12L),
                ClockOffsetSample(offsetMs = 1_000L, roundTripMs = 20L),
                ClockOffsetSample(offsetMs = 1_040L, roundTripMs = 30L),
                ClockOffsetSample(offsetMs = -8_000L, roundTripMs = 12_000L)
            )
        )
        assertEquals(1_000L, estimate?.offsetMs)
        assertEquals(20L, estimate?.medianRoundTripMs)
    }

    @Test
    fun serverClockEstimatorRejectsOnlyHighLatencySamples() {
        assertEquals(
            null,
            ServerClockEstimator.estimate(
                listOf(
                    ClockOffsetSample(offsetMs = 1_000L, roundTripMs = 10_001L),
                    ClockOffsetSample(offsetMs = 1_020L, roundTripMs = 12_000L),
                    ClockOffsetSample(offsetMs = 1_040L, roundTripMs = 15_000L)
                )
            )
        )
    }
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
        assertEquals(0.99f, ahead.speed)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(400L, false, 1_000L).type)
        val reversed = controller.evaluate(-400L, false, 1_500L)
        assertEquals(DriftCorrectionType.RESET_SPEED, reversed.type)
        assertEquals(
            DriftCorrectionType.NONE,
            controller.evaluate(-400L, false, 2_000L).type
        )
        assertEquals(
            DriftCorrectionType.NONE,
            controller.evaluate(-400L, false, 2_500L).type
        )
        val settledBehind = controller.evaluate(-400L, false, 3_000L)
        assertEquals(DriftCorrectionType.SET_SPEED, settledBehind.type)
        assertTrue(settledBehind.speed > 1f && settledBehind.speed <= 1.01f)
    }

    @Test
    fun laggingPlayerIsGentlySpedUp() {
        val controller = DriftCorrectionController()
        controller.evaluate(-350L, false, 0L)
        val behind = controller.evaluate(-350L, false, 500L)
        assertEquals(DriftCorrectionType.SET_SPEED, behind.type)
        assertEquals(1.01f, behind.speed)
    }

    @Test
    fun largeDriftSeeksOnlyAfterThreeSamplesAndRespectsCooldown() {
        val controller = DriftCorrectionController()
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_900L, false, 0L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_900L, false, 500L).type)
        assertEquals(DriftCorrectionType.SEEK, controller.evaluate(1_900L, false, 1_000L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_900L, false, 2_000L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_900L, false, 4_000L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_900L, false, 8_000L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_900L, false, 12_000L).type)
        assertEquals(DriftCorrectionType.SEEK, controller.evaluate(1_900L, false, 13_000L).type)
    }

    @Test
    fun bufferingNeverTriggersCorrection() {
        val controller = DriftCorrectionController()
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_500L, true, 0L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_500L, true, 500L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_500L, true, 1_000L).type)
    }

    @Test
    fun highRoundTripTimeWidensDeadZoneToAvoidChasingNetworkJitter() {
        val controller = DriftCorrectionController()
        repeat(4) { index ->
            assertEquals(
                DriftCorrectionType.NONE,
                controller.evaluate(
                    driftMs = 200L,
                    isBuffering = false,
                    nowMs = index * 1_000L,
                    roundTripMs = 400L
                ).type
            )
        }
    }

    @Test
    fun highRoundTripTimeRaisesHardSeekThreshold() {
        val controller = DriftCorrectionController()
        repeat(5) { index ->
            val correction = controller.evaluate(
                driftMs = 2_200L,
                isBuffering = false,
                nowMs = index * 1_000L,
                roundTripMs = 1_000L
            )
            assertTrue(correction.type != DriftCorrectionType.SEEK)
        }
    }

    @Test
    fun returningToDeadZoneResetsTemporarySpeedAfterFilteredDriftSettles() {
        val controller = DriftCorrectionController()
        controller.evaluate(300L, false, 0L)
        assertEquals(DriftCorrectionType.SET_SPEED, controller.evaluate(300L, false, 500L).type)
        // EMA filtering avoids instantly reacting to one low/noisy sample.
        assertEquals(DriftCorrectionType.SET_SPEED, controller.evaluate(50L, false, 1_000L).type)
        assertEquals(DriftCorrectionType.RESET_SPEED, controller.evaluate(50L, false, 1_500L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(50L, false, 2_000L).type)
    }

    @Test
    fun seekThresholdIsNotTriggeredByMinorDifference() {
        assertFalse(RoomPlaybackMath.needsSeek(10_000L, 10_500L))
        assertTrue(RoomPlaybackMath.needsSeek(10_000L, 11_000L))
    }

    @Test
    fun driftSeekGateWaitsForTheFirstSeekInsteadOfAllowingAnother() {
        val gate = DriftSeekGate()
        gate.begin(10_000L, 1_000L)
        assertTrue(gate.shouldWait(8_000L, true, false, true, 2_000L))
        assertTrue(gate.shouldWait(8_000L, false, true, true, 3_000L))
        assertFalse(gate.shouldWait(12_000L, false, true, true, 3_000L))
        assertFalse(gate.shouldWait(12_000L, false, true, true, 4_000L))
    }

    @Test
    fun driftSeekGateReleasesAfterTimeoutWhenTheSeekNeverConverges() {
        val gate = DriftSeekGate()
        gate.begin(10_000L, 1_000L)
        assertTrue(gate.shouldWait(8_000L, false, true, true, 8_999L))
        assertFalse(gate.shouldWait(8_000L, false, true, true, 9_000L))
        assertFalse(gate.shouldWait(8_000L, false, true, true, 9_001L))
    }

    @Test
    fun failedRemoteApplyUsesBoundedBackoff() {
        val policy = RemoteApplyRetryPolicy()
        assertFalse(policy.shouldRetry(4L, 0L))
        assertEquals(1_000L, policy.recordFailure(4L, 0L))
        assertFalse(policy.shouldRetry(4L, 999L))
        assertTrue(policy.shouldRetry(4L, 1_000L))
        assertEquals(2_000L, policy.recordFailure(4L, 1_000L))
        assertFalse(policy.shouldRetry(4L, 2_999L))
        assertTrue(policy.shouldRetry(4L, 3_000L))
    }

    @Test
    fun newerRevisionIsNeverBlockedByOlderRetry() {
        val policy = RemoteApplyRetryPolicy()
        policy.recordFailure(4L, 0L)
        assertFalse(policy.shouldRetry(5L, 100L))
        assertEquals(1_000L, policy.recordFailure(5L, 100L))
        assertTrue(policy.shouldRetry(5L, 1_100L))
    }

    @Test
    fun successfulRemoteApplyClearsBackoff() {
        val policy = RemoteApplyRetryPolicy()
        policy.recordFailure(4L, 0L)
        policy.recordSuccess(4L)
        assertFalse(policy.shouldRetry(4L, 10_000L))
        assertEquals(1_000L, policy.recordFailure(4L, 10_000L))
    }
}
