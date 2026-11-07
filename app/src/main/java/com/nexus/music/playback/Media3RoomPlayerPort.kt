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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext

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

    private var onRoomPlaybackStarted: (() -> Unit)? = null

    fun setRoomPlaybackStartedListener(listener: () -> Unit) {
        onRoomPlaybackStarted = listener
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
        onRoomPlaybackStarted?.invoke()
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

}

/**
 * Captures command intent before it reaches ExoPlayer. Room corrections bypass this
 * wrapper and call Media3RoomPlayerPort directly, so a remote correction cannot echo.
 */
@OptIn(UnstableApi::class)
internal class RoomIntentForwardingPlayer(
    private val delegate: Player,
    private val recordIntent: (RoomPlayerIntent) -> Unit,
    private val isRoomActive: () -> Boolean = { false },
    private val shouldHoldRoomPlay: () -> Boolean = { false }
) : androidx.media3.common.ForwardingPlayer(delegate) {
    private var pendingRoomTrackStart = false

    fun clearPendingRoomTrackStart() {
        pendingRoomTrackStart = false
    }

    override fun play() {
        recordIntent(RoomPlayerIntent.Play)
        if (isRoomActive() && (pendingRoomTrackStart || shouldHoldRoomPlay())) {
            // A newly selected track or an active shared-start barrier must stay staged.
            delegate.pause()
        } else {
            super.play()
        }
    }

    override fun pause() {
        recordIntent(RoomPlayerIntent.Pause)
        pendingRoomTrackStart = false
        super.pause()
    }

    override fun seekTo(positionMs: Long) {
        val targetPositionMs = positionMs.coerceAtLeast(0L)
        recordIntent(RoomPlayerIntent.SeekTo(targetPositionMs))
        super.seekTo(targetPositionMs)
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
        if (!playWhenReady) pendingRoomTrackStart = false
        if (playWhenReady && isRoomActive() &&
            (pendingRoomTrackStart || shouldHoldRoomPlay())
        ) {
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
        shouldPlay = delegate.playWhenReady
    )

    private fun finishSkip(previous: SkipState, next: Boolean) {
        val changedTrack = delegate.currentMediaItem?.mediaId?.let { it != previous.sourceId } == true
        if (isRoomActive() && changedTrack) {
            pendingRoomTrackStart = previous.shouldPlay
            if (previous.shouldPlay) delegate.pause()
        }
        recordIntent(
            if (next) {
                RoomPlayerIntent.SkipToNext(previous.sourceId, previous.positionMs, previous.shouldPlay)
            } else {
                RoomPlayerIntent.SkipToPrevious(previous.sourceId, previous.positionMs, previous.shouldPlay)
            }
        )
    }

    override fun seekToNext() {
        val previous = captureSkipState()
        super.seekToNext()
        finishSkip(previous, next = true)
    }

    override fun seekToNextMediaItem() {
        val previous = captureSkipState()
        super.seekToNextMediaItem()
        finishSkip(previous, next = true)
    }

    override fun seekToPrevious() {
        val previous = captureSkipState()
        super.seekToPrevious()
        finishSkip(previous, next = false)
    }

    override fun seekToPreviousMediaItem() {
        val previous = captureSkipState()
        super.seekToPreviousMediaItem()
        finishSkip(previous, next = false)
    }

    override fun stop() {
        recordIntent(RoomPlayerIntent.Pause)
        pendingRoomTrackStart = false
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
        pendingRoomTrackStart = isRoomActive() && shouldPlay
        // Do not let a new item inherit the previous item's active playWhenReady.
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
