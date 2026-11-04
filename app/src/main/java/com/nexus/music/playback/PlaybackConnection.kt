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
    val durationMs: Long = 0L
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
    private var pendingRequest: PlaybackRequest? = null
    private var pendingPlayWhenReady = true

    private val controllerFuture = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java))
    ).buildAsync()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publishState()
        }

        override fun onPlayerError(error: PlaybackException) {
            onError(error.message ?: "Playback failed. Try selecting the track again.")
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
                pendingRequest?.let { startPlayback(it, pendingPlayWhenReady) }
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

    fun play(audio: ResolvedAudio, shouldPlay: Boolean = true) {
        val request = PlaybackRequest.from(audio)
        pendingRequest = request
        pendingPlayWhenReady = shouldPlay
        if (controller != null) {
            startPlayback(request, shouldPlay)
            pendingRequest = null
            pendingPlayWhenReady = true
        }
    }

    private fun startPlayback(request: PlaybackRequest, shouldPlay: Boolean) {
        val activeController = controller ?: return
        try {
            val metadata = MediaMetadata.Builder()
                .setTitle(request.title)
                .setArtist(request.artist ?: "")
                .setArtworkUri(request.thumbnailUrl?.let(Uri::parse))
                .build()
            val item = MediaItem.Builder()
                .setMediaId(request.sourceId)
                .setUri(request.streamUrl)
                .setMediaMetadata(metadata)
                .build()

            activeController.setMediaItem(item)
            activeController.prepare()
            if (shouldPlay) activeController.play() else activeController.pause()
            publishState()
        } catch (error: Exception) {
            onError(error.message ?: "The selected audio could not be sent to the player.")
        }
    }

    fun togglePlayback() {
        val activeController = controller ?: return
        if (activeController.isPlaying) {
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

    fun setPlaying(shouldPlay: Boolean) {
        val activeController = controller ?: return
        if (shouldPlay) {
            if (activeController.playbackState == Player.STATE_IDLE ||
                activeController.playbackState == Player.STATE_ENDED
            ) {
                if (activeController.playbackState == Player.STATE_ENDED) {
                    activeController.seekTo(0L)
                }
                activeController.prepare()
            }
            activeController.play()
        } else {
            activeController.pause()
        }
        publishState()
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
            durationMs = duration
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
