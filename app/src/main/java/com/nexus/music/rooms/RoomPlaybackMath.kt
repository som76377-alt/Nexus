package com.nexus.music.rooms

/**
 * Position projection and anti-oscillation policy for two-device playback.
 * Small drift is ignored; sustained moderate drift is corrected gradually;
 * only sustained large drift causes one local seek, protected by a cooldown.
 */
internal object RoomPlaybackMath {
    fun expectedRemotePositionMs(
        positionMs: Long,
        isPlaying: Boolean,
        elapsedSinceUpdateMs: Long,
        durationMs: Long
    ): Long {
        val elapsed = if (isPlaying) elapsedSinceUpdateMs.coerceAtLeast(0L) else 0L
        val projected = safeAdd(positionMs.coerceAtLeast(0L), elapsed)
        return if (durationMs > 0L) projected.coerceAtMost(durationMs) else projected
    }

    fun distanceMs(first: Long, second: Long): Long {
        val a = first.coerceAtLeast(0L)
        val b = second.coerceAtLeast(0L)
        return if (a >= b) a - b else b - a
    }

    fun needsSeek(
        currentPositionMs: Long,
        expectedPositionMs: Long,
        thresholdMs: Long = 1_000L
    ): Boolean = distanceMs(currentPositionMs, expectedPositionMs) >= thresholdMs

    private fun safeAdd(first: Long, second: Long): Long {
        if (Long.MAX_VALUE - first < second) return Long.MAX_VALUE
        return first + second
    }
}

internal enum class DriftCorrectionType { NONE, SET_SPEED, RESET_SPEED, SEEK }

internal data class DriftCorrection(
    val type: DriftCorrectionType,
    val speed: Float = 1f
)

/**
 * Stateful hysteresis: a single noisy sample never triggers a correction.
 * A seek is rate-limited, and buffering samples never trigger recovery loops.
 */
internal class DriftCorrectionController(
    private val deadZoneMs: Long = 160L,
    private val seekThresholdMs: Long = 1_800L,
    private val seekCooldownMs: Long = 12_000L,
    private val minimumSamples: Int = 3,
    private val emaAlpha: Double = 0.35,
    private val speedGain: Double = 0.03
) {
    private var consecutiveDriftSamples = 0
    private var lastSeekAtMs = Long.MIN_VALUE
    private var currentSpeed = 1f
    private var filteredDriftMs = 0.0
    private var hasDriftSample = false

    fun evaluate(
        driftMs: Long,
        isBuffering: Boolean,
        nowMs: Long,
        roundTripMs: Long = 0L
    ): DriftCorrection {
        // Never interpret a buffering position as real playback drift.
        if (isBuffering) return DriftCorrection(DriftCorrectionType.NONE)

        filteredDriftMs = if (hasDriftSample) {
            emaAlpha * driftMs.toDouble() + (1.0 - emaAlpha) * filteredDriftMs
        } else {
            hasDriftSample = true
            driftMs.toDouble()
        }

        val safeRttMs = roundTripMs.coerceIn(0L, 5_000L)
        // Avoid chasing network jitter. Higher latency widens the ignore zone slightly.
        val adaptiveDeadZoneMs = maxOf(
            deadZoneMs,
            (safeRttMs / 2L + 40L).coerceAtMost(350L)
        )
        val adaptiveSeekThresholdMs = maxOf(
            seekThresholdMs,
            safeRttMs * 2L + 500L
        )
        val magnitude = kotlin.math.abs(filteredDriftMs)

        if (magnitude <= adaptiveDeadZoneMs) {
            consecutiveDriftSamples = 0
            if (currentSpeed != 1f) {
                currentSpeed = 1f
                return DriftCorrection(DriftCorrectionType.RESET_SPEED)
            }
            return DriftCorrection(DriftCorrectionType.NONE)
        }

        consecutiveDriftSamples += 1

        // A seek requires sustained large drift and a cooldown. One noisy sample cannot
        // interrupt playback or create a seek/buffer/seek loop.
        if (magnitude >= adaptiveSeekThresholdMs &&
            consecutiveDriftSamples >= minimumSamples &&
            (lastSeekAtMs == Long.MIN_VALUE || nowMs - lastSeekAtMs >= seekCooldownMs)
        ) {
            lastSeekAtMs = nowMs
            consecutiveDriftSamples = 0
            currentSpeed = 1f
            return DriftCorrection(DriftCorrectionType.SEEK)
        }

        // Moderate drift is corrected gradually, with pitch-preserving Media3 speed changes.
        if (magnitude < adaptiveSeekThresholdMs && consecutiveDriftSamples >= 2) {
            // Positive drift means this device is ahead, so it must slow down.
            val adjustment = (filteredDriftMs / 1_000.0 * speedGain)
                .coerceIn(-0.01, 0.01)
            val speed = (1.0 - adjustment).toFloat().coerceIn(0.99f, 1.01f)
            if (kotlin.math.abs(speed - currentSpeed) >= 0.001f) {
                currentSpeed = speed
                return DriftCorrection(DriftCorrectionType.SET_SPEED, speed)
            }
        }
        return DriftCorrection(DriftCorrectionType.NONE)
    }

    fun reset() {
        consecutiveDriftSamples = 0
        currentSpeed = 1f
        filteredDriftMs = 0.0
        hasDriftSample = false
        lastSeekAtMs = Long.MIN_VALUE
    }
}


/**
 * Prevents a second hard seek while the first one is still being applied by Media3.
 *
 * A successful seek can briefly put ExoPlayer into BUFFERING. Treating that temporary
 * state as fresh drift caused the old controller to issue another seek as soon as the
 * player became ready, creating a seek/buffer/seek loop.
 */
internal class DriftSeekGate(
    private val completionToleranceMs: Long = 500L,
    private val timeoutMs: Long = 8_000L
) {
    private var targetPositionMs: Long? = null
    private var issuedAtElapsedMs: Long = 0L

    fun begin(targetPositionMs: Long, nowElapsedMs: Long) {
        this.targetPositionMs = targetPositionMs.coerceAtLeast(0L)
        issuedAtElapsedMs = nowElapsedMs
    }

    fun shouldWait(
        localPositionMs: Long,
        isBuffering: Boolean,
        isPlaying: Boolean,
        remoteIsPlaying: Boolean,
        nowElapsedMs: Long
    ): Boolean {
        val target = targetPositionMs ?: return false
        if (isBuffering || !isPlaying) return true

        val elapsed = (nowElapsedMs - issuedAtElapsedMs).coerceAtLeast(0L)
        val projectedTarget = if (remoteIsPlaying) {
            RoomPlaybackMath.expectedRemotePositionMs(
                positionMs = target,
                isPlaying = true,
                elapsedSinceUpdateMs = elapsed,
                durationMs = 0L
            )
        } else {
            target
        }
        if (RoomPlaybackMath.distanceMs(localPositionMs, projectedTarget) <= completionToleranceMs) {
            reset()
            return false
        }
        if (elapsed < timeoutMs) return true

        reset()
        return false
    }

    fun reset() {
        targetPositionMs = null
        issuedAtElapsedMs = 0L
    }
}

/**
 * Back off a failed remote-track application without delaying a newer room revision.
 * A new revision is never blocked by an older revision's retry timer.
 */
internal class RemoteApplyRetryPolicy(
    private val initialDelayMs: Long = 1_000L,
    private val maximumDelayMs: Long = 15_000L
) {
    private var failedRevision: Long? = null
    private var failureCount = 0
    private var retryAtElapsedMs = 0L

    fun shouldRetry(revision: Long, nowElapsedMs: Long): Boolean =
        failedRevision == revision && nowElapsedMs >= retryAtElapsedMs

    fun recordFailure(revision: Long, nowElapsedMs: Long): Long {
        if (failedRevision != revision) {
            failedRevision = revision
            failureCount = 0
        }
        failureCount = (failureCount + 1).coerceAtMost(10)
        val exponent = (failureCount - 1).coerceAtMost(10)
        val multiplier = 1L shl exponent
        val delayMs = if (initialDelayMs > maximumDelayMs / multiplier) {
            maximumDelayMs
        } else {
            (initialDelayMs * multiplier).coerceAtMost(maximumDelayMs)
        }
        retryAtElapsedMs = if (Long.MAX_VALUE - nowElapsedMs < delayMs) {
            Long.MAX_VALUE
        } else {
            nowElapsedMs + delayMs
        }
        return delayMs
    }

    fun recordSuccess(revision: Long) {
        if (failedRevision == revision) reset()
    }

    fun reset() {
        failedRevision = null
        failureCount = 0
        retryAtElapsedMs = 0L
    }
}


/** One server-time probe measured against the device's monotonic clock. */
internal data class ClockOffsetSample(
    val offsetMs: Long,
    val roundTripMs: Long
)

internal data class ClockOffsetEstimate(
    val offsetMs: Long,
    val medianRoundTripMs: Long
)

/**
 * Selects the three lowest-latency valid probes, then uses their median offset and RTT.
 * This makes one delayed mobile-network response much less influential on room timing.
 */
internal object ServerClockEstimator {
    fun estimate(samples: List<ClockOffsetSample>): ClockOffsetEstimate? {
        val best = samples
            .filter { it.roundTripMs in 0L..10_000L }
            .sortedBy { it.roundTripMs }
            .take(3)
        if (best.size < 3) return null

        val offsets = best.map { it.offsetMs }.sorted()
        val roundTrips = best.map { it.roundTripMs }.sorted()
        return ClockOffsetEstimate(
            offsetMs = offsets[offsets.size / 2],
            medianRoundTripMs = roundTrips[roundTrips.size / 2]
        )
    }

    fun estimateOffset(samples: List<ClockOffsetSample>): Long? =
        estimate(samples)?.offsetMs
}
