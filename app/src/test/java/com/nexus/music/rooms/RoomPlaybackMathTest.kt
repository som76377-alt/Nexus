package com.nexus.music.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomPlaybackMathTest {
    @Test
    fun serverClockEstimatorUsesMedianOfThreeFastestSamples() {
        val estimate = ServerClockEstimator.estimate(
            listOf(
                ClockOffsetSample(offsetMs = 1_000L, roundTripMs = 20L),
                ClockOffsetSample(offsetMs = 1_040L, roundTripMs = 30L),
                ClockOffsetSample(offsetMs = 1_020L, roundTripMs = 25L),
                ClockOffsetSample(offsetMs = 9_000L, roundTripMs = 900L),
                ClockOffsetSample(offsetMs = -8_000L, roundTripMs = 1_200L)
            )
        )
        assertEquals(1_020L, estimate?.offsetMs)
    }

    @Test
    fun serverClockEstimatorRejectsInsufficientSamples() {
        assertEquals(
            null,
            ServerClockEstimator.estimate(
                listOf(
                    ClockOffsetSample(offsetMs = 1_000L, roundTripMs = 20L),
                    ClockOffsetSample(offsetMs = 1_020L, roundTripMs = 30L)
                )
            )?.offsetMs
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
        assertEquals(0.98f, ahead.speed)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(400L, false, 1_000L).type)
        val reversed = controller.evaluate(-400L, false, 1_500L)
        assertEquals(DriftCorrectionType.RESET_SPEED, reversed.type)
        assertEquals(
            DriftCorrectionType.NONE,
            controller.evaluate(-400L, false, 2_000L).type
        )
        val initialBehind = controller.evaluate(-400L, false, 2_500L)
        assertEquals(DriftCorrectionType.SET_SPEED, initialBehind.type)
        assertTrue(initialBehind.speed > 1f && initialBehind.speed <= 1.02f)
        val settledBehind = controller.evaluate(-400L, false, 3_000L)
        assertEquals(DriftCorrectionType.SET_SPEED, settledBehind.type)
        assertTrue(settledBehind.speed > initialBehind.speed && settledBehind.speed <= 1.02f)
    }

    @Test
    fun laggingPlayerIsGentlySpedUp() {
        val controller = DriftCorrectionController()
        controller.evaluate(-350L, false, 0L)
        val behind = controller.evaluate(-350L, false, 500L)
        assertEquals(DriftCorrectionType.SET_SPEED, behind.type)
        assertTrue(behind.speed > 1f && behind.speed <= 1.02f)
    }

    @Test
    fun largeDriftSeeksOnlyAfterThreeSamplesAndRespectsCooldown() {
        val controller = DriftCorrectionController()
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_000L, false, 0L).type)
        assertEquals(DriftCorrectionType.SEEK, controller.evaluate(1_000L, false, 500L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_000L, false, 1_000L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_000L, false, 4_000L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_000L, false, 6_000L).type)
        assertEquals(DriftCorrectionType.SEEK, controller.evaluate(1_000L, false, 6_500L).type)
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

    // SPEC: S3 — measured seek latency is used only as bounded local target compensation.
    @Test
    fun seekCompensationUsesDefaultLatencyWhenNoSamplesExist() {
        assertEquals(10_400L, RoomPlaybackMath.adjustedSeekPositionMs(10_000L, emptyList()))
    }

    @Test
    fun seekCompensationAveragesOnlyTheLastThreeValidSamples() {
        assertEquals(
            10_666L,
            RoomPlaybackMath.adjustedSeekPositionMs(
                10_000L,
                listOf(200L, 400L, 600L, 1_000L)
            )
        )
    }

    @Test
    fun seekCompensationCapsLatencyAndClampsToTrackDuration() {
        assertEquals(
            11_500L,
            RoomPlaybackMath.adjustedSeekPositionMs(10_000L, listOf(5_000L))
        )
        assertEquals(
            10_000L,
            RoomPlaybackMath.adjustedSeekPositionMs(9_900L, listOf(1_500L), 10_000L)
        )
    }

    @Test
    fun seekCompensationIgnoresInvalidLatencySamples() {
        assertEquals(
            10_400L,
            RoomPlaybackMath.adjustedSeekPositionMs(10_000L, listOf(-1L, 20_000L))
        )
    }

    @Test
    fun seekThresholdIsNotTriggeredByMinorDifference() {
        assertFalse(RoomPlaybackMath.needsSeek(10_000L, 10_699L))
        assertTrue(RoomPlaybackMath.needsSeek(10_000L, 10_700L))
    }


    // SPEC: S3 — release the seek barrier as soon as the local player is ready and playing.
    @Test
    fun driftSeekGateWaitsDuringBufferingAndReleasesWhenPlaybackResumes() {
        val gate = DriftSeekGate()
        gate.begin(10_000L, 1_000L)
        assertTrue(gate.shouldWait(true, false, 2_000L))
        assertFalse(gate.shouldWait(false, true, 3_000L))
        assertFalse(gate.isPending())
        assertFalse(gate.shouldWait(false, true, 4_000L))
    }

    @Test
    fun driftSeekGateTimesOutAfterTwoSecondsIfPlaybackNeverResumes() {
        val gate = DriftSeekGate()
        gate.begin(10_000L, 1_000L)
        assertTrue(gate.shouldWait(true, false, 2_999L))
        assertFalse(gate.shouldWait(true, false, 3_000L))
        assertFalse(gate.isPending())
    }

    // SPEC: S3 — one immediate post-buffer correction at or above 250 ms per revision.
    @Test
    fun postSeekCorrectionGateAllowsOneCorrectionPerRevision() {
        val gate = PostSeekCorrectionGate()
        gate.begin(7L)
        assertTrue(gate.consume(7L, -250L))
        gate.begin(7L)
        assertFalse(gate.consume(7L, 400L))
        gate.begin(8L)
        assertFalse(gate.consume(8L, 249L))
        gate.begin(9L)
        assertTrue(gate.consume(9L, 300L))
    }

    @Test
    fun failedRemoteApplyUsesBoundedBackoff() {
        val policy = RemoteApplyRetryPolicy()
        assertTrue(policy.shouldAttempt(4L, 0L, hasUnappliedChanges = true))
        assertEquals(1_000L, policy.recordFailure(4L, 0L))
        assertFalse(policy.shouldAttempt(4L, 999L, hasUnappliedChanges = true))
        assertTrue(policy.shouldAttempt(4L, 1_000L, hasUnappliedChanges = true))
        assertEquals(2_000L, policy.recordFailure(4L, 1_000L))
        assertFalse(policy.shouldAttempt(4L, 2_999L, hasUnappliedChanges = true))
        assertTrue(policy.shouldAttempt(4L, 3_000L, hasUnappliedChanges = true))
    }

    @Test
    fun newerRevisionIsNeverBlockedByOlderRetry() {
        val policy = RemoteApplyRetryPolicy()
        policy.recordFailure(4L, 0L)
        assertTrue(policy.shouldAttempt(5L, 100L, hasUnappliedChanges = true))
        assertEquals(1_000L, policy.recordFailure(5L, 100L))
        assertFalse(policy.shouldAttempt(5L, 1_099L, hasUnappliedChanges = true))
        assertTrue(policy.shouldAttempt(5L, 1_100L, hasUnappliedChanges = true))
    }

    @Test
    fun successfulRemoteApplyClearsBackoff() {
        val policy = RemoteApplyRetryPolicy()
        policy.recordFailure(4L, 0L)
        policy.recordSuccess(4L)
        assertTrue(policy.shouldAttempt(4L, 10_000L, hasUnappliedChanges = true))
        assertEquals(1_000L, policy.recordFailure(4L, 10_000L))
    }
    @Test
    fun halfSecondDriftGetsAPlaybackRateCorrectionAfterTwoStableSamples() {
        val controller = DriftCorrectionController()
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(500L, false, 0L).type)
        val correction = controller.evaluate(500L, false, 1_000L)
        assertEquals(DriftCorrectionType.SET_SPEED, correction.type)
        assertTrue(correction.speed <= 0.98f)
    }

    @Test
    fun roomTimestampParserRejectsTrailingGarbage() {
        assertNull(RoomTimestampParser.parse("2026-11-06T12:30:00.123456Zgarbage"))
        assertNull(RoomTimestampParser.parse("2026-11-06T12:30:00Zjunk"))
    }

    @Test
    fun bufferingBreaksConsecutiveDriftSamples() {
        val controller = DriftCorrectionController()
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_000L, false, 0L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_000L, true, 500L).type)
        assertEquals(DriftCorrectionType.NONE, controller.evaluate(1_000L, false, 1_000L).type)
        assertEquals(DriftCorrectionType.SEEK, controller.evaluate(1_000L, false, 1_500L).type)
    }

    @Test
    fun roomTimestampParserHandlesPostgresMicrosecondsAndOffsets() {
        val utc = RoomTimestampParser.parse("2026-11-06T09:00:00.123456Z")
        val offset = RoomTimestampParser.parse("2026-11-06T14:30:00.123456+05:30")
        assertEquals(utc, offset)
        assertEquals(null, RoomTimestampParser.parse("not-a-timestamp"))
    }

}
