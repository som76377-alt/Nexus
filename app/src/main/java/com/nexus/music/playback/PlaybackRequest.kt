package com.nexus.music.playback

import com.nexus.music.catalog.ResolvedAudio

/** Short-lived handoff. Keep canonical identity separate from the expiring stream URL. */
data class PlaybackRequest(
    val sourceId: String,
    val streamUrl: String,
    val title: String,
    val artist: String?,
    val thumbnailUrl: String?,
    val mimeType: String?
) {
    companion object {
        fun from(audio: ResolvedAudio) = PlaybackRequest(
            sourceId = audio.sourceUrl,
            streamUrl = audio.audioUrl,
            title = audio.title,
            artist = audio.artist,
            thumbnailUrl = audio.thumbnailUrl,
            mimeType = audio.mimeType
        )
    }
}
