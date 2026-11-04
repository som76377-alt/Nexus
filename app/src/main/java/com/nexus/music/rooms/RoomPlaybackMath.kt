package com.nexus.music.rooms

/**
 * Pure playback-position calculations shared by the room synchronizer and its unit tests.
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

    fun expectedLocalPositionMs(
        positionMs: Long,
        isPlaying: Boolean,
        lastPublishedElapsedMs: Long,
        nowElapsedMs: Long
    ): Long {
        val elapsed = if (isPlaying) {
            (nowElapsedMs - lastPublishedElapsedMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return safeAdd(positionMs.coerceAtLeast(0L), elapsed)
    }

    fun needsSeek(
        currentPositionMs: Long,
        expectedPositionMs: Long,
        thresholdMs: Long = 1_400L
    ): Boolean = distance(currentPositionMs, expectedPositionMs) > thresholdMs

    fun shouldPublishPosition(
        currentPositionMs: Long,
        expectedPositionMs: Long,
        thresholdMs: Long = 650L
    ): Boolean = distance(currentPositionMs, expectedPositionMs) >= thresholdMs

    private fun distance(first: Long, second: Long): Long {
        val a = first.coerceAtLeast(0L)
        val b = second.coerceAtLeast(0L)
        return if (a >= b) a - b else b - a
    }

    private fun safeAdd(first: Long, second: Long): Long {
        if (Long.MAX_VALUE - first < second) return Long.MAX_VALUE
        return first + second
    }
}
