package com.nexus.music.playback

import com.nexus.music.rooms.domain.RoomPlayerIntent

/** Converts a completed MediaSession skip into the concrete shared timeline intent. */
internal fun resolveSkipIntent(
    previousSourceId: String,
    previousPositionMs: Long,
    shouldPlay: Boolean,
    currentSourceId: String,
    currentTitle: String,
    currentArtist: String?,
    currentPositionMs: Long
): RoomPlayerIntent? {
    if (currentSourceId.isNotBlank() && currentTitle.isNotBlank() &&
        currentSourceId != previousSourceId
    ) {
        return RoomPlayerIntent.SelectTrack(
            sourceId = currentSourceId,
            canonicalSourceUrl = currentSourceId,
            title = currentTitle,
            artist = currentArtist,
            shouldPlay = shouldPlay
        )
    }
    val position = currentPositionMs.coerceAtLeast(0L)
    return if (position != previousPositionMs) RoomPlayerIntent.SeekTo(position) else null
}
