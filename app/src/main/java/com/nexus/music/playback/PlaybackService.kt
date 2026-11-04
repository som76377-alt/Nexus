package com.nexus.music.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns playback independently of MainActivity.
 *
 * MediaSessionService supplies the ongoing media notification and system media controls while
 * playback is active. ExoPlayer's local wake mode holds a partial wake lock only while audio is
 * ready/buffering and playWhenReady is true; it does not keep the phone awake indefinitely.
 */
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val musicAudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                setAudioAttributes(musicAudioAttributes, /* handleAudioFocus = */ true)
                setHandleAudioBecomingNoisy(true)
            }

        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    // MediaSessionService keeps the service alive when a session is actively playing and stops
    // it when playback is no longer active. Keep the platform default onTaskRemoved behaviour.

    override fun onDestroy() {
        mediaSession?.release()
        player?.release()
        mediaSession = null
        player = null
        super.onDestroy()
    }
}
