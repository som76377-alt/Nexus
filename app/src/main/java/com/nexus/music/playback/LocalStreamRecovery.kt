package com.nexus.music.playback

import com.nexus.music.catalog.ResolvedAudio
import com.nexus.music.catalog.StreamProvider

/**
 * Performs stream-error recovery as a local player repair.
 *
 * There is intentionally no room-action publisher in this contract: re-resolution is
 * a system recovery, not an explicit user tap (SPEC S5), and must not echo into the room.
 */
internal class LocalStreamRecovery(
    private val resolveNext: suspend (String, Set<StreamProvider>) -> ResolvedAudio,
    private val isCurrentSource: (String) -> Boolean,
    private val replaceLocally: (ResolvedAudio, Long, Boolean) -> Unit
) {
    suspend fun recover(
        sourceUrl: String,
        excludedProviders: Set<StreamProvider>,
        resumePositionMs: Long,
        resumeWhenReady: Boolean
    ): Boolean {
        val replacement = resolveNext(sourceUrl, excludedProviders)
        if (!isCurrentSource(sourceUrl)) return false
        replaceLocally(replacement, resumePositionMs.coerceAtLeast(0L), resumeWhenReady)
        return true
    }
}
