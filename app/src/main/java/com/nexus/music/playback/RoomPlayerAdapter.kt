package com.nexus.music.playback

import com.nexus.music.catalog.ResolvedAudio

/**
 * The room synchronizer talks to this small player port, not directly to Media3.
 * Each Android build can supply its own adapter while keeping the room protocol unchanged.
 */
interface RoomPlayerAdapter : AutoCloseable {
    fun play(audio: ResolvedAudio, shouldPlay: Boolean = true, userInitiated: Boolean = true): Boolean
    fun togglePlayback()
    fun seekTo(positionMs: Long, userInitiated: Boolean = true): Boolean
    fun setPlaying(shouldPlay: Boolean, positionMs: Long? = null)
    fun setPlaybackSpeed(speed: Float)
    fun resetPlaybackSpeed()
    fun snapshot(): PlaybackSnapshot
    override fun close()
}
