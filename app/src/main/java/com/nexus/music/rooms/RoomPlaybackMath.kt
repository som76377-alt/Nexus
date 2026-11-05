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
    private val minimumSamples: Int = 3
) {
    private var consecutiveDriftSamples = 0
    private var lastSeekAtMs = Long.MIN_VALUE
    private var currentSpeed = 1f

    fun evaluate(driftMs: Long, isBuffering: Boolean, nowMs: Long): DriftCorrection {
        if (isBuffering) return DriftCorrection(DriftCorrectionType.NONE)

        val magnitude = kotlin.math.abs(driftMs)
        if (magnitude <= deadZoneMs) {
            consecutiveDriftSamples = 0
            if (currentSpeed != 1f) {
                currentSpeed = 1f
                return DriftCorrection(DriftCorrectionType.RESET_SPEED)
            }
            return DriftCorrection(DriftCorrectionType.NONE)
        }

        consecutiveDriftSamples += 1
        if (magnitude >= seekThresholdMs &&
            consecutiveDriftSamples >= minimumSamples &&
            (lastSeekAtMs == Long.MIN_VALUE || nowMs - lastSeekAtMs >= seekCooldownMs)
        ) {
            lastSeekAtMs = nowMs
            consecutiveDriftSamples = 0
            currentSpeed = 1f
            return DriftCorrection(DriftCorrectionType.SEEK)
        }

        if (consecutiveDriftSamples >= 2 && magnitude < seekThresholdMs) {
            // Positive drift means this player is ahead and must slow down.
            val speed = if (driftMs > 0L) 0.99f else 1.01f
            if (currentSpeed != speed) {
                currentSpeed = speed
                return DriftCorrection(DriftCorrectionType.SET_SPEED, speed)
            }
        }
        return DriftCorrection(DriftCorrectionType.NONE)
    }

    fun reset() {
        consecutiveDriftSamples = 0
        currentSpeed = 1f
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
