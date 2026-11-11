package com.nexus.music.ui

/**
 * Pure motion math for the vinyl player. Kept free of Android APIs so timing and
 * tracking behavior can be exercised by local JVM tests.
 */
internal object VinylMotion {
    enum class DeckState { STOPPED, SPINNING_UP, PLAYING, SPINNING_DOWN }
    enum class ArmPhase { RESTING, LIFTING, SWINGING_TO_GROOVE, LOWERING, TRACKING, SWINGING_TO_REST }

    fun progressToAngle(
        positionMs: Long,
        durationMs: Long,
        startAngle: Float,
        endAngle: Float
    ): Float {
        if (durationMs <= 0L) return startAngle
        val progress = (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
        return startAngle + (endAngle - startAngle) * progress.toFloat()
    }

    /** Smoothstep ramp: monotonic, bounded, and with zero slope at either end. */
    fun speedRamp(progress: Float, accelerating: Boolean): Float {
        val t = progress.coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)
        return if (accelerating) eased else 1f - eased
    }

    fun deckStateFor(shouldPlay: Boolean, currentSpeed: Float): DeckState {
        val speed = currentSpeed.coerceIn(0f, 1f)
        return when {
            shouldPlay && speed >= 0.999f -> DeckState.PLAYING
            shouldPlay -> DeckState.SPINNING_UP
            speed <= 0.001f -> DeckState.STOPPED
            else -> DeckState.SPINNING_DOWN
        }
    }

    /** Blend from the live value, not a stale animation endpoint, for rapid toggles. */
    fun blend(current: Float, target: Float, fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        return current + (target - current) * t
    }

    fun trackingAngle(positionMs: Long, durationMs: Long, startAngle: Float, endAngle: Float): Float =
        progressToAngle(positionMs, durationMs, startAngle, endAngle)
}
