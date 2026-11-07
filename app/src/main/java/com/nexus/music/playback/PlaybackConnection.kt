package com.nexus.music.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.nexus.music.catalog.ResolvedAudio
import java.util.concurrent.Executor

data class PlaybackSnapshot(
    val connected: Boolean,
    val sourceId: String = "",
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
)

/** Activity-side bridge to the player owned by PlaybackService. */
class PlaybackConnection(
    context: Context,
    private val onReady: () -> Unit,
    private val onStateChanged: (PlaybackSnapshot) -> Unit,
    private val onError: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command ->
        if (Looper.myLooper() == Looper.getMainLooper()) command.run()
        else mainHandler.post(command)
    }

    private var closed = false
    private var controller: MediaController? = null
    private var pendingRequest: ResolvedAudio? = null
    private var pendingPlayWhenReady = true

    private val controllerFuture = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java))
    ).buildAsync()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publishState()
        }

        @UnstableApi
        override fun onPlayerError(error: PlaybackException) {
            val detail = buildList {
                add(error.errorCodeName)
                error.cause?.message?.takeIf(String::isNotBlank)?.let(::add)
                error.message?.takeIf(String::isNotBlank)?.let(::add)
            }.distinct().joinToString(" · ")
            onError(detail.ifBlank { "Playback failed. Try selecting the track again." })
            publishState()
        }
    }

    init {
        controllerFuture.addListener({
            if (closed) return@addListener
            try {
                val connectedController = controllerFuture.get()
                controller = connectedController
                connectedController.addListener(playerListener)
                pendingRequest?.let {
                    startPlayback(it, pendingPlayWhenReady)
                }
                pendingRequest = null
                pendingPlayWhenReady = true
                onReady()
                publishState()
            } catch (error: Exception) {
                val cause = error.cause ?: error
                onError(cause.message ?: "Could not connect to the playback service.")
            }
        }, mainExecutor)
    }

    fun play(
        audio: ResolvedAudio,
        shouldPlay: Boolean = true
    ): Boolean {
        val request = audio
        val activeController = controller
        if (activeController == null) {
            pendingRequest = request
            pendingPlayWhenReady = shouldPlay
            return true
        }
        pendingRequest = null
        pendingPlayWhenReady = true
        return startPlayback(request, shouldPlay)
    }

    private fun startPlayback(
        audio: ResolvedAudio,
        shouldPlay: Boolean
    ): Boolean {
        val activeController = controller ?: return false
        return try {
            val metadata = MediaMetadata.Builder()
                .setTitle(audio.title)
                .setArtist(audio.artist ?: "")
                .setArtworkUri(audio.thumbnailUrl?.let(Uri::parse))
                .build()
            val item = MediaItem.Builder()
                .setMediaId(audio.sourceUrl)
                .setUri(audio.audioUrl)
                .setMimeType(audio.mimeType)
                .setMediaMetadata(metadata)
                .build()

            activeController.setMediaItem(item)
            activeController.prepare()
            if (shouldPlay) activeController.play() else activeController.pause()
            publishState()
            true
        } catch (error: Exception) {
            onError(error.message ?: "The selected audio could not be sent to the player.")
            false
        }
    }

    fun togglePlayback() {
        val activeController = controller ?: return
        if (activeController.playWhenReady) {
            activeController.pause()
        } else {
            if (activeController.playbackState == Player.STATE_IDLE ||
                activeController.playbackState == Player.STATE_ENDED
            ) {
                if (activeController.playbackState == Player.STATE_ENDED) {
                    activeController.seekTo(0L)
                }
                activeController.prepare()
            }
            activeController.play()
        }
        publishState()
    }

    fun seekTo(positionMs: Long): Boolean {
        val activeController = controller ?: return false
        if (!activeController.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            return false
        }
        activeController.seekTo(positionMs.coerceAtLeast(0L))
        return true
    }

    fun snapshot(): PlaybackSnapshot {
        val activeController = controller ?: return PlaybackSnapshot(connected = false)
        val item = activeController.currentMediaItem
        val duration = activeController.duration.let {
            if (it == C.TIME_UNSET || it < 0L) 0L else it
        }
        return PlaybackSnapshot(
            connected = true,
            sourceId = item?.mediaId.orEmpty(),
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
            isPlaying = activeController.isPlaying,
            playbackState = activeController.playbackState,
            positionMs = activeController.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            playWhenReady = activeController.playWhenReady,
            isBuffering = activeController.playbackState == Player.STATE_BUFFERING,
        )
    }

    private fun publishState() {
        if (!closed) onStateChanged(snapshot())
    }

    fun close() {
        if (closed) return
        closed = true
        controller?.removeListener(playerListener)
        controller = null
        pendingRequest = null
        MediaController.releaseFuture(controllerFuture)
    }
}
