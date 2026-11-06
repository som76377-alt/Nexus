package com.nexus.music.rooms

import com.nexus.music.catalog.ResolvedAudio
import com.nexus.music.rooms.domain.LocalPlaybackSnapshot
import com.nexus.music.rooms.domain.RoomActionType
import com.nexus.music.rooms.domain.RoomClock
import com.nexus.music.rooms.domain.RoomCommand
import com.nexus.music.rooms.domain.RoomPlaybackSnapshot
import com.nexus.music.rooms.domain.RoomPlayerIntent
import com.nexus.music.rooms.domain.RoomPlayerPort
import com.nexus.music.rooms.domain.RoomPublishResult
import com.nexus.music.rooms.domain.RoomRealtimeSignal
import com.nexus.music.rooms.domain.RoomRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRoomControllerTest {

    @Test
    fun attachPublishesAnAlreadyLoadedLocalTrackThroughRevisionCas() = runTest {
        val canonical = "https://www.youtube.com/watch?v=local-track"
        val repository = FakeRoomRepository()
        val player = FakeRoomPlayer(
            LocalPlaybackSnapshot(
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Local track",
                artist = "Local artist",
                isPlaying = false,
                isReady = true,
                positionMs = 0L,
                durationMs = 120_000L
            )
        )
        val controller = newController(repository, player)

        try {
            controller.attach("room-1")
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()

            assertEquals(1, repository.published.size)
            val command = repository.published.single()
            assertEquals(RoomActionType.TRACK, command.actionType)
            assertEquals(canonical, command.sourceId)
            assertEquals(canonical, command.canonicalSourceUrl)
            assertEquals(0L, command.expectedRevision)
            assertEquals("device-1", command.originDeviceId)
            assertFalse(command.isPlaying)
            assertEquals(1L, repository.snapshot?.revision)
        } finally {
            controller.close()
        }
    }

    @Test
    fun remoteTrackAndPlayAreAppliedWithoutEchoingAnotherRoomCommand() = runTest {
        val canonical = "https://www.youtube.com/watch?v=remote-track"
        val repository = FakeRoomRepository(
            initialSnapshot = RoomPlaybackSnapshot(
                roomId = "room-2",
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Remote track",
                artist = "Remote artist",
                isPlaying = true,
                positionMs = 0L,
                durationMs = 180_000L,
                revision = 7L,
                updatedBy = "other-device",
                updatedAt = "2026-11-06T00:00:00.000Z",
                actionId = "remote-action",
                actionType = RoomActionType.TRACK,
                originDeviceId = "other-device",
                commandAt = "2026-11-06T00:00:00.000Z"
            )
        )
        val player = FakeRoomPlayer()
        val controller = newController(repository, player)

        try {
            controller.attach("room-2")
            runCurrent()
            advanceTimeBy(50L)
            runCurrent()

            assertEquals(canonical, player.snapshot().canonicalSourceUrl)
            assertTrue(player.snapshot().isReady)
            assertTrue(player.snapshot().isPlaying)
            assertEquals(0, repository.published.size)
            assertEquals(7L, controller.state.value.playback?.revision)
        } finally {
            controller.close()
        }
    }

    @Test
    fun chatInvalidationUpdatesUiStateWithoutTouchingPlaybackRevision() = runTest {
        val repository = FakeRoomRepository()
        val player = FakeRoomPlayer()
        val controller = newController(repository, player)

        try {
            controller.attach("room-3")
            runCurrent()
            repository.emit(RoomRealtimeSignal.ChatInvalidated)
            runCurrent()

            assertEquals(1L, controller.state.value.chatSignalVersion)
            assertEquals(0L, controller.state.value.playback?.revision ?: 0L)
            assertTrue(repository.published.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun detachStopsTheActiveRoomSessionAndClearsItsUiState() = runTest {
        val repository = FakeRoomRepository()
        val player = FakeRoomPlayer()
        val controller = newController(repository, player)

        try {
            controller.attach("room-4")
            runCurrent()
            controller.detach()
            runCurrent()

            assertEquals(null, controller.state.value.roomId)
            assertTrue(controller.state.value.playback == null)
            assertEquals(0, repository.leaveCount)
        } finally {
            controller.close()
        }
    }

    private fun newController(
        repository: FakeRoomRepository,
        player: FakeRoomPlayer
    ) = ServiceRoomController(
        parentScope = this,
        repository = repository,
        player = player,
        clock = FakeRoomClock(),
        mediaResolver = FakeRoomMediaResolver()
    )

    private class FakeRoomRepository(
        initialSnapshot: RoomPlaybackSnapshot? = null
    ) : RoomRepository {
        val published = mutableListOf<RoomCommand>()
        val signals = MutableSharedFlow<RoomRealtimeSignal>(extraBufferCapacity = 16)
        var snapshot: RoomPlaybackSnapshot? = initialSnapshot
        var leaveCount = 0

        override suspend fun currentUserId(): String = "device-1"

        override suspend fun fetchPlaybackSnapshot(roomId: String): RoomPlaybackSnapshot? =
            snapshot?.takeIf { it.roomId == roomId }

        override suspend fun publishPlayback(command: RoomCommand): RoomPublishResult {
            published += command
            val revision = command.expectedRevision + 1L
            snapshot = RoomPlaybackSnapshot(
                roomId = command.roomId,
                sourceId = command.sourceId,
                canonicalSourceUrl = command.canonicalSourceUrl,
                title = command.title,
                artist = command.artist,
                isPlaying = command.isPlaying,
                positionMs = command.positionMs,
                durationMs = command.durationMs,
                revision = revision,
                updatedBy = command.originDeviceId,
                updatedAt = "2026-11-06T00:00:00.000Z",
                actionId = command.actionId,
                actionType = command.actionType,
                originDeviceId = command.originDeviceId,
                commandAt = "2026-11-06T00:00:00.000Z"
            )
            return RoomPublishResult(applied = true, revision = revision)
        }

        override fun observeRealtimeSignals(roomId: String): SharedFlow<RoomRealtimeSignal> =
            signals

        override suspend fun touchRoom(roomId: String): Boolean = true

        override suspend fun leaveRoom(roomId: String) {
            leaveCount += 1
        }

        fun emit(signal: RoomRealtimeSignal) {
            signals.tryEmit(signal)
        }
    }

    private class FakeRoomPlayer(
        initial: LocalPlaybackSnapshot = LocalPlaybackSnapshot()
    ) : RoomPlayerPort {
        private val mutablePlayback = MutableStateFlow(initial)
        private val mutableIntents = MutableSharedFlow<RoomPlayerIntent>(extraBufferCapacity = 16)

        override val playback: StateFlow<LocalPlaybackSnapshot> = mutablePlayback
        override val userIntents: SharedFlow<RoomPlayerIntent> = mutableIntents

        override fun snapshot(): LocalPlaybackSnapshot = mutablePlayback.value

        override suspend fun prepareCanonicalSource(
            sourceId: String,
            canonicalSourceUrl: String,
            streamUrl: String,
            title: String,
            artist: String?,
            mimeType: String?
        ) {
            mutablePlayback.value = mutablePlayback.value.copy(
                sourceId = sourceId,
                canonicalSourceUrl = canonicalSourceUrl,
                title = title,
                artist = artist,
                isReady = true,
                isBuffering = false,
                isPlaying = false,
                positionMs = 0L
            )
        }

        override fun play() {
            mutablePlayback.value = mutablePlayback.value.copy(
                isPlaying = true,
                isBuffering = false,
                isReady = true
            )
        }

        override fun pause() {
            mutablePlayback.value = mutablePlayback.value.copy(isPlaying = false)
        }

        override fun seekTo(positionMs: Long) {
            mutablePlayback.value = mutablePlayback.value.copy(
                positionMs = positionMs.coerceAtLeast(0L)
            )
        }

        override fun setPlaybackSpeed(speed: Float) = Unit

        override fun resetPlaybackSpeed() = Unit

        fun emitIntent(intent: RoomPlayerIntent) {
            mutableIntents.tryEmit(intent)
        }
    }

    private class FakeRoomClock : RoomClock {
        private var elapsedMs = 10_000L
        override fun elapsedRealtimeMs(): Long = elapsedMs
        override fun estimatedServerTimeMs(): Long = elapsedMs
        override fun estimatedRoundTripTimeMs(): Long = 25L
        override suspend fun calibrate() {
            elapsedMs += 1L
        }
    }

    private class FakeRoomMediaResolver : RoomMediaResolver {
        override suspend fun resolve(
            sourceId: String,
            canonicalSourceUrl: String
        ) = ResolvedAudio(
            sourceUrl = canonicalSourceUrl,
            title = "Resolved track",
            artist = "Resolved artist",
            thumbnailUrl = null,
            durationSeconds = 180L,
            audioUrl = "https://stream.invalid/audio",
            averageBitrate = 128_000,
            mimeType = "audio/mp4"
        )
    }
}
