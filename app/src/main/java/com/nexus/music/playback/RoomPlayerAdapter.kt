package com.nexus.music.playback

import com.nexus.music.catalog.ResolvedAudio

/** Activity-side bridge to the single player owned by PlaybackService. */
interface RoomPlayerAdapter : AutoCloseable {
    fun play(audio: ResolvedAudio, shouldPlay: Boolean = true): Boolean
    fun togglePlayback()
    fun seekTo(positionMs: Long): Boolean
    fun snapshot(): PlaybackSnapshot
    override fun close()
}
