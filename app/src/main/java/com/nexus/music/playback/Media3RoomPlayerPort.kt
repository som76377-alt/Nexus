package com.nexus.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.nexus.music.rooms.domain.LocalPlaybackSnapshot
import com.nexus.music.rooms.domain.RoomPlayerIntent
import com.nexus.music.rooms.domain.RoomPlayerPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * A narrow adapter around the single ExoPlayer in PlaybackService.
 * Remote room application calls this adapter directly; local user intent is
 * emitted only by the ForwardingPlayer that fronts the MediaSession.
 */
@OptIn(UnstableApi::class)
internal class Media3RoomPlayerPort(
    private val player: ExoPlayer
) : RoomPlayerPort, AutoCloseable {
    private val mutablePlayback = MutableStateFlow(readSnapshot())
    private val mutableUserIntents = MutableSharedFlow<RoomPlayerIntent>(
        extraBufferCapacity = 32
    )
    private var systemPauseForwarded = false

    override val playback: StateFlow<LocalPlaybackSnapshot> = mutablePlayback
    override val userIntents: SharedFlow<RoomPlayerIntent> = mutableUserIntents

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            mutablePlayback.value = readSnapshot()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            mutablePlayback.value = readSnapshot()
            if (playWhenReady) {
                systemPauseForwarded = false
            } else if (
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ||
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG
            ) {
                forwardSystemPause()
            }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            mutablePlayback.value = readSnapshot()
            if (playbackSuppressionReason ==
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
            ) {
                // Transient focus loss suppresses output while playWhenReady can remain true.
                // Pause locally too, so a later focus return cannot resume one phone alone.
                forwardSystemPause(pauseLocal = true)
            } else if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                player.playWhenReady
            ) {
                systemPauseForwarded = false
            }
        }

        private fun forwardSystemPause(pauseLocal: Boolean = false) {
            if (systemPauseForwarded) return
            systemPauseForwarded = true
            if (pauseLocal && player.playWhenReady) {
                player.pause()
            }
            mutableUserIntents.tryEmit(RoomPlayerIntent.Pause)
        }
    }

    init {
        player.addListener(listener)
    }

    /** Called by the MediaSession-facing ForwardingPlayer for a genuine controller action. */
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
        sourceId: String,
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
            mutablePlayback.value = readSnapshot()
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

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed.coerceIn(0.98f, 1.02f))
    }

    override fun resetPlaybackSpeed() {
        player.setPlaybackSpeed(1f)
    }

    override fun close() {
        player.removeListener(listener)
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
    private val isRoomActive: () -> Boolean = { false }
) : androidx.media3.common.ForwardingPlayer(delegate) {

    override fun play() {
        recordIntent(RoomPlayerIntent.Play)
        if (isRoomActive()) {
            // User intent is published by the room controller; local playback is released
            // only when the shared start barrier completes.
            delegate.pause()
        } else {
            super.play()
        }
    }

    override fun pause() {
        recordIntent(RoomPlayerIntent.Pause)
        super.pause()
    }

    override fun seekTo(positionMs: Long) {
        recordIntent(RoomPlayerIntent.SeekTo(positionMs.coerceAtLeast(0L)))
        super.seekTo(positionMs)
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
        recordIntent(if (playWhenReady) RoomPlayerIntent.Play else RoomPlayerIntent.Pause)
        if (playWhenReady && isRoomActive()) {
            delegate.pause()
        } else {
            super.setPlayWhenReady(playWhenReady)
        }
    }

    override fun seekToNext() {
        val previousSourceId = delegate.currentMediaItem?.mediaId.orEmpty()
        val previousPositionMs = delegate.currentPosition.coerceAtLeast(0L)
        val shouldPlay = delegate.playWhenReady
        super.seekToNext()
        val changedTrack = delegate.currentMediaItem?.mediaId?.let { it != previousSourceId } == true
        if (isRoomActive() && shouldPlay && changedTrack) {
            // MediaSession skip methods act synchronously on the wrapped player. Stop the
            // newly selected item before the next audio render; the room barrier owns resume.
            delegate.pause()
        }
        recordIntent(RoomPlayerIntent.SkipToNext(
            previousSourceId = previousSourceId,
            previousPositionMs = previousPositionMs,
            shouldPlay = shouldPlay
        ))
    }

    override fun seekToNextMediaItem() {
        val previousSourceId = delegate.currentMediaItem?.mediaId.orEmpty()
        val previousPositionMs = delegate.currentPosition.coerceAtLeast(0L)
        val shouldPlay = delegate.playWhenReady
        super.seekToNextMediaItem()
        val changedTrack = delegate.currentMediaItem?.mediaId?.let { it != previousSourceId } == true
        if (isRoomActive() && shouldPlay && changedTrack) {
            // MediaSession skip methods act synchronously on the wrapped player. Stop the
            // newly selected item before the next audio render; the room barrier owns resume.
            delegate.pause()
        }
        recordIntent(RoomPlayerIntent.SkipToNext(
            previousSourceId = previousSourceId,
            previousPositionMs = previousPositionMs,
            shouldPlay = shouldPlay
        ))
    }

    override fun seekToPrevious() {
        val previousSourceId = delegate.currentMediaItem?.mediaId.orEmpty()
        val previousPositionMs = delegate.currentPosition.coerceAtLeast(0L)
        val shouldPlay = delegate.playWhenReady
        super.seekToPrevious()
        val changedTrack = delegate.currentMediaItem?.mediaId?.let { it != previousSourceId } == true
        if (isRoomActive() && shouldPlay && changedTrack) {
            // MediaSession skip methods act synchronously on the wrapped player. Stop the
            // newly selected item before the next audio render; the room barrier owns resume.
            delegate.pause()
        }
        recordIntent(RoomPlayerIntent.SkipToPrevious(
            previousSourceId = previousSourceId,
            previousPositionMs = previousPositionMs,
            shouldPlay = shouldPlay
        ))
    }

    override fun seekToPreviousMediaItem() {
        val previousSourceId = delegate.currentMediaItem?.mediaId.orEmpty()
        val previousPositionMs = delegate.currentPosition.coerceAtLeast(0L)
        val shouldPlay = delegate.playWhenReady
        super.seekToPreviousMediaItem()
        val changedTrack = delegate.currentMediaItem?.mediaId?.let { it != previousSourceId } == true
        if (isRoomActive() && shouldPlay && changedTrack) {
            // MediaSession skip methods act synchronously on the wrapped player. Stop the
            // newly selected item before the next audio render; the room barrier owns resume.
            delegate.pause()
        }
        recordIntent(RoomPlayerIntent.SkipToPrevious(
            previousSourceId = previousSourceId,
            previousPositionMs = previousPositionMs,
            shouldPlay = shouldPlay
        ))
    }

    override fun stop() {
        recordIntent(RoomPlayerIntent.Pause)
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
        val shouldPlay = delegate.playWhenReady
        // Do not let a new item inherit the previous item's active playWhenReady.
        // The intent is preserved and released only after the room start barrier.
        if (shouldPlay) delegate.pause()
        recordIntent(
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
