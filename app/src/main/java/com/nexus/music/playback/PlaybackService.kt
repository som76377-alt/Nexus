package com.nexus.music.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** ExoPlayer and its MediaSession outlive the search Activity. */
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val musicAudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val exoPlayer = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(musicAudioAttributes, /* handleAudioFocus = */ true)
            setHandleAudioBecomingNoisy(true)
        }
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        player?.release()
        mediaSession = null
        player = null
        super.onDestroy()
    }
}
