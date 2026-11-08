package com.nexus.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.nexus.music.rooms.ServiceRoomController
import com.nexus.music.rooms.domain.LocalPlaybackSnapshot
import com.nexus.music.rooms.domain.RoomPlayerIntent
import com.nexus.music.rooms.domain.RoomPlayerPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * A narrow adapter around the single ExoPlayer in PlaybackService.
 * Remote room application calls this adapter directly; local user intent is
 * emitted only by the ForwardingPlayer that fronts the MediaSession.
 */
@OptIn(UnstableApi::class)
internal class Media3RoomPlayerPort(
    private val player: ExoPlayer
) : RoomPlayerPort {
    private val mutableUserIntents = MutableSharedFlow<RoomPlayerIntent>(
        extraBufferCapacity = 32
    )

    override val userIntents: SharedFlow<RoomPlayerIntent> = mutableUserIntents
    private val pendingSeekStartedAtMs = AtomicLong(-1L)
    private val completedSeekLatencyMs = AtomicLong(-1L)

    private val interruptionListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            finishSeekMeasurementIfPlaying()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) finishSeekMeasurementIfPlaying()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && (
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                    )
            ) {
                // ExoPlayer changes its own playWhenReady for permanent focus loss and
                // becoming-noisy events; neither is an explicit room Pause command.
                recordUserIntent(RoomPlayerIntent.SystemPause)
            }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            when (playbackSuppressionReason) {
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> {
                    recordUserIntent(RoomPlayerIntent.SystemPause)
                }
                Player.PLAYBACK_SUPPRESSION_REASON_NONE -> {
                    // Transient focus regain resumes automatically without a MediaSession command.
                    recordUserIntent(RoomPlayerIntent.SystemResume)
                }
            }
        }
    }

    init {
        player.addListener(interruptionListener)
    }

    /** Emit a user command or platform interruption observed at the player boundary. */
    fun recordUserIntent(intent: RoomPlayerIntent) {
        mutableUserIntents.tryEmit(intent)
    }

    override fun snapshot(): LocalPlaybackSnapshot = readSnapshot()

    private fun readSnapshot(): LocalPlaybackSnapshot {
        val item = player.currentMediaItem
        val duration = player.duration.let {
            if (it == C.TIME_UNSET || it < 0L) 0L else it
        }
        val canonicalUrl = item?.mediaId.orEmpty()
        return LocalPlaybackSnapshot(
            sourceId = canonicalUrl,
            canonicalSourceUrl = canonicalUrl,
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            artist = item?.mediaMetadata?.artist?.toString(),
            playWhenReady = player.playWhenReady,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            isReady = player.playbackState == Player.STATE_READY,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration
        )
    }

    override suspend fun prepareCanonicalSource(
        canonicalSourceUrl: String,
        streamUrl: String,
        title: String,
        artist: String?,
        mimeType: String?
    ) {
        withContext(Dispatchers.Main.immediate) {
            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist.orEmpty())
                .build()
            val mediaItem = MediaItem.Builder()
                .setMediaId(canonicalSourceUrl)
                .setUri(streamUrl)
                .setMimeType(mimeType)
                .setMediaMetadata(metadata)
                .build()
            player.setMediaItem(mediaItem)
            player.prepare()
        }
    }

    override fun play() {
        when (player.playbackState) {
            Player.STATE_IDLE -> player.prepare()
            Player.STATE_ENDED -> {
                player.seekTo(0L)
                player.prepare()
            }
        }
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    fun recordSeekRequested() {
        pendingSeekStartedAtMs.set(android.os.SystemClock.elapsedRealtime())
    }

    override fun seekTo(positionMs: Long) {
        recordSeekRequested()
        player.seekTo(positionMs.coerceAtLeast(0L))
        finishSeekMeasurementIfPlaying()
    }

    override fun consumeSeekLatencyMs(): Long? =
        completedSeekLatencyMs.getAndSet(-1L).takeIf { it >= 0L }

    fun checkSeekCompletion() = finishSeekMeasurementIfPlaying()

    private fun finishSeekMeasurementIfPlaying() {
        if (player.playbackState != Player.STATE_READY || !player.isPlaying) return
        val startedAt = pendingSeekStartedAtMs.getAndSet(-1L)
        if (startedAt >= 0L) {
            val latencyMs = (android.os.SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            if (latencyMs <= 15_000L) completedSeekLatencyMs.set(latencyMs)
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed.coerceIn(0.98f, 1.02f))
    }

    override fun resetPlaybackSpeed() {
        player.setPlaybackSpeed(1f)
    }

}

/**
 * Captures command intent before it reaches ExoPlayer. Room corrections bypass this
 * wrapper and call Media3RoomPlayerPort directly, so a remote correction cannot echo.
 */
@OptIn(UnstableApi::class)
internal class RoomIntentForwardingPlayer(
    private val delegate: Player,
    private val recordIntent: (RoomPlayerIntent) -> Unit,
    private val playbackPhase: (RoomPlayerIntent?) -> ServiceRoomController.PlaybackPhase,
    private val onSeekRequested: () -> Unit = {},
    private val onSeekCompleted: () -> Unit = {}
) : androidx.media3.common.ForwardingPlayer(delegate) {
    private fun emitIntent(intent: RoomPlayerIntent) {
        playbackPhase(intent)
        recordIntent(intent)
    }

    override fun play() {
        emitIntent(RoomPlayerIntent.Play)
        if (playbackPhase(null).holdsPlay) {
            // A newly selected track or an active shared-start barrier must stay staged.
            delegate.pause()
        } else {
            super.play()
        }
    }

    override fun pause() {
        emitIntent(RoomPlayerIntent.Pause)
        super.pause()
    }

    override fun seekTo(positionMs: Long) {
        val targetPositionMs = positionMs.coerceAtLeast(0L)
        onSeekRequested()
        emitIntent(RoomPlayerIntent.SeekTo(targetPositionMs))
        super.seekTo(targetPositionMs)
        onSeekCompleted()
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        recordTrack(mediaItem)
        super.setMediaItem(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        recordTrack(mediaItem)
        super.setMediaItem(mediaItem, resetPosition)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        recordTrack(mediaItem)
        super.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        recordPlaylistSelection(mediaItems, 0)
        super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        val selectedIndex = if (resetPosition) 0 else delegate.currentMediaItemIndex
        recordPlaylistSelection(mediaItems, selectedIndex)
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ) {
        val selectedIndex = if (startIndex == C.INDEX_UNSET) {
            delegate.currentMediaItemIndex
        } else {
            startIndex
        }
        recordPlaylistSelection(mediaItems, selectedIndex)
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        emitIntent(if (playWhenReady) RoomPlayerIntent.Play else RoomPlayerIntent.Pause)
        if (playWhenReady && playbackPhase(null).holdsPlay) {
            delegate.pause()
        } else {
            super.setPlayWhenReady(playWhenReady)
        }
    }

    private data class SkipState(
        val sourceId: String,
        val positionMs: Long,
        val shouldPlay: Boolean
    )

    private fun captureSkipState() = SkipState(
        sourceId = delegate.currentMediaItem?.mediaId.orEmpty(),
        positionMs = delegate.currentPosition.coerceAtLeast(0L),
        shouldPlay = playbackPhase(null).let { if (it.isRoomActive) it.shouldPlay else delegate.playWhenReady }
    )

    private fun finishSkip(previous: SkipState) {
        val item = delegate.currentMediaItem ?: return
        val changedTrack = item.mediaId.isNotBlank() && item.mediaId != previous.sourceId
        if (changedTrack && playbackPhase(null).isRoomActive) delegate.pause()
        val intent = resolveSkipIntent(
            previousSourceId = previous.sourceId,
            previousPositionMs = previous.positionMs,
            shouldPlay = previous.shouldPlay,
            currentSourceId = item.mediaId,
            currentTitle = item.mediaMetadata.title?.toString().orEmpty(),
            currentArtist = item.mediaMetadata.artist?.toString(),
            currentPositionMs = delegate.currentPosition
        ) ?: return
        emitIntent(intent)
    }

    private inline fun skip(action: () -> Unit) {
        val previous = captureSkipState()
        action()
        finishSkip(previous)
    }

    override fun seekToNext() = skip { delegate.seekToNext() }
    override fun seekToNextMediaItem() = skip { delegate.seekToNextMediaItem() }
    override fun seekToPrevious() = skip { delegate.seekToPrevious() }
    override fun seekToPreviousMediaItem() = skip { delegate.seekToPreviousMediaItem() }

    override fun stop() {
        emitIntent(RoomPlayerIntent.Pause)
        super.stop()
    }

    private fun recordPlaylistSelection(items: List<MediaItem>, selectedIndex: Int) {
        val selected = items.getOrNull(selectedIndex)
            ?: items.firstOrNull()
            ?: return
        recordTrack(selected)
    }

    private fun recordTrack(mediaItem: MediaItem) {
        // The media ID is the canonical shared identity. Never fall back to the
        // local playback URI: it is an expiring extracted stream URL, not room state.
        val canonicalUrl = mediaItem.mediaId.takeIf(String::isNotBlank) ?: return
        val title = mediaItem.mediaMetadata.title?.toString().orEmpty()
        if (canonicalUrl.isBlank() || title.isBlank()) return
        val phase = playbackPhase(null)
        val shouldPlay = if (phase.isRoomActive) phase.shouldPlay else delegate.playWhenReady
        // Room playback is staged by the coordinator; outside a room, preserve normal
        // MediaSession auto-play behavior when a controller replaces the media item.
        if (phase.isRoomActive) delegate.pause()
        emitIntent(
            RoomPlayerIntent.SelectTrack(
                sourceId = canonicalUrl,
                canonicalSourceUrl = canonicalUrl,
                title = title,
                artist = mediaItem.mediaMetadata.artist?.toString(),
                shouldPlay = shouldPlay
            )
        )
    }
}

