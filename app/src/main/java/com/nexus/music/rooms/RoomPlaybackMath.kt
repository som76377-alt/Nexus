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
    private val deadZoneMs: Long = 100L,
    private val seekThresholdMs: Long = 900L,
    private val seekCooldownMs: Long = 5_000L,
    private val minimumSamples: Int = 3
) {
    private var consecutiveDriftSamples = 0
    private var lastSeekAtMs = Long.MIN_VALUE
    private var speedCorrectionActive = false

    fun evaluate(driftMs: Long, isBuffering: Boolean, nowMs: Long): DriftCorrection {
        if (isBuffering) return DriftCorrection(DriftCorrectionType.NONE)

        val magnitude = kotlin.math.abs(driftMs)
        if (magnitude <= deadZoneMs) {
            consecutiveDriftSamples = 0
            if (speedCorrectionActive) {
                speedCorrectionActive = false
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
            speedCorrectionActive = false
            return DriftCorrection(DriftCorrectionType.SEEK)
        }

        if (consecutiveDriftSamples >= 2 && magnitude < seekThresholdMs) {
            speedCorrectionActive = true
            // Positive drift means this player is ahead and must slow down.
            val speed = if (driftMs > 0L) 0.98f else 1.02f
            return DriftCorrection(DriftCorrectionType.SET_SPEED, speed)
        }
        return DriftCorrection(DriftCorrectionType.NONE)
    }

    fun reset() {
        consecutiveDriftSamples = 0
        speedCorrectionActive = false
        lastSeekAtMs = Long.MIN_VALUE
    }
}
