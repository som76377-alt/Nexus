package com.nexus.music.rooms

import com.nexus.music.catalog.ResolvedAudio
import com.nexus.music.rooms.domain.LocalPlaybackSnapshot
import com.nexus.music.rooms.domain.RoomActionType
import com.nexus.music.rooms.domain.RoomClock
import com.nexus.music.rooms.domain.RoomCommand
import com.nexus.music.rooms.domain.RoomPlaybackSnapshot
import com.nexus.music.rooms.domain.RoomPlaybackReadyResult
import com.nexus.music.rooms.domain.RoomPlayerIntent
import com.nexus.music.rooms.domain.RoomPlayerPort
import com.nexus.music.rooms.domain.RoomPublishResult
import com.nexus.music.rooms.domain.RoomRealtimeSignal
import com.nexus.music.rooms.domain.RoomRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
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
    fun bufferedTrackPublishesExplicitPlayIntentWithoutWaitingForAudioAdvancement() = runTest {
        val canonical = "https://www.youtube.com/watch?v=buffered-track"
        val repository = FakeRoomRepository()
        val player = FakeRoomPlayer(
            LocalPlaybackSnapshot(
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Buffered track",
                artist = "Artist",
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                isReady = false,
                positionMs = 0L,
                durationMs = 90_000L
            )
        )
        val controller = newController(repository, player)

        try {
            controller.attach("room-buffering")
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()

            assertEquals(1, repository.published.size)
            assertEquals(RoomActionType.TRACK, repository.published.single().actionType)
            assertTrue("play intent must survive buffering", repository.published.single().isPlaying)
            assertFalse("the new track remains paused until the room barrier completes", player.snapshot().playWhenReady)
            assertFalse(player.snapshot().isPlaying)
            assertEquals("a buffering local stream is not READY yet", 0, repository.readinessCalls)
        } finally {
            controller.close()
        }
    }

    @Test
    fun explicitPauseDuringBufferingPublishesPausedIntent() = runTest {
        val canonical = "https://www.youtube.com/watch?v=pause-buffering"
        val repository = FakeRoomRepository()
        val player = FakeRoomPlayer(
            LocalPlaybackSnapshot(
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Pause while buffering",
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                durationMs = 90_000L
            )
        )
        val controller = newController(repository, player)

        try {
            controller.attach("room-pause-buffering")
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()
            assertTrue(repository.published.single().isPlaying)

            player.setSnapshot(player.snapshot().copy(playWhenReady = false))
            player.emitIntent(RoomPlayerIntent.Pause)
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()

            assertEquals(2, repository.published.size)
            assertEquals(RoomActionType.PAUSE, repository.published.last().actionType)
            assertFalse(repository.published.last().isPlaying)
        } finally {
            controller.close()
        }
    }

    @Test
    fun explicitNextAndPreviousCommandsPublishTheSelectedCanonicalTrack() = runTest {
        val first = "https://www.youtube.com/watch?v=first-track"
        val second = "https://www.youtube.com/watch?v=second-track"
        val third = "https://www.youtube.com/watch?v=third-track"
        val repository = FakeRoomRepository()
        val player = FakeRoomPlayer(
            LocalPlaybackSnapshot(
                sourceId = first,
                canonicalSourceUrl = first,
                title = "First track",
                playWhenReady = true,
                isPlaying = true,
                isReady = true,
                positionMs = 12_000L,
                durationMs = 120_000L
            )
        )
        val controller = newController(repository, player)

        try {
            controller.attach("room-skip")
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()
            assertEquals(first, repository.published.last().sourceId)

            player.setSnapshot(
                player.snapshot().copy(
                    sourceId = second,
                    canonicalSourceUrl = second,
                    title = "Second track",
                    positionMs = 0L
                )
            )
            player.emitIntent(
                RoomPlayerIntent.SkipToNext(
                    previousSourceId = first,
                    previousPositionMs = 12_000L
                )
            )
            runCurrent()
            advanceTimeBy(250L)
            runCurrent()

            assertEquals(second, repository.published.last().sourceId)
            assertEquals(RoomActionType.TRACK, repository.published.last().actionType)

            player.setSnapshot(
                player.snapshot().copy(
                    sourceId = third,
                    canonicalSourceUrl = third,
                    title = "Third track",
                    positionMs = 0L
                )
            )
            player.emitIntent(
                RoomPlayerIntent.SkipToPrevious(
                    previousSourceId = second,
                    previousPositionMs = 0L
                )
            )
            runCurrent()
            advanceTimeBy(250L)
            runCurrent()

            assertEquals(third, repository.published.last().sourceId)
            assertEquals(RoomActionType.TRACK, repository.published.last().actionType)
            assertEquals(3, repository.published.size)
        } finally {
            controller.close()
        }
    }

    @Test
    fun localTrackStartsOnlyAfterTheServerIssuesTheSharedReadyBarrier() = runTest {
        val canonical = "https://www.youtube.com/watch?v=barrier-local-track"
        val repository = FakeRoomRepository()
        repository.scheduledStartAt = "1970-01-01T00:00:11.501Z"
        val player = FakeRoomPlayer(
            LocalPlaybackSnapshot(
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Barrier track",
                artist = "Artist",
                playWhenReady = true,
                isPlaying = true,
                isReady = true,
                positionMs = 0L,
                durationMs = 90_000L
            )
        )
        val clock = FakeRoomClock()
        val controller = newController(repository, player, clock = clock)

        try {
            controller.attach("room-barrier-local")
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()

            assertEquals(1, repository.published.size)
            assertTrue(repository.published.single().isPlaying)
            assertTrue(repository.readinessCalls >= 1)
            assertFalse("track must wait for the scheduled start", player.snapshot().playWhenReady)
            assertEquals("barrier progress is not an error", null, controller.state.value.lastError)
            assertEquals("Preparing synchronized start…", controller.state.value.statusMessage)

            clock.advance(1_501L)
            advanceTimeBy(250L)
            runCurrent()

            assertTrue(player.snapshot().playWhenReady)
            assertTrue(player.snapshot().isPlaying)
            assertEquals(1_501L, player.snapshot().positionMs)
            assertEquals(1L, controller.state.value.playback?.revision)
            assertEquals(null, controller.state.value.statusMessage)

            // Drift correction must project from the exact position at the scheduled
            // anchor, not combine the old command position with the new anchor time.
            clock.advance(1_000L)
            player.setSnapshot(player.snapshot().copy(positionMs = 2_501L))
            advanceTimeBy(1_000L)
            runCurrent()
            clock.advance(1_000L)
            player.setSnapshot(player.snapshot().copy(positionMs = 3_501L))
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(3_501L, player.snapshot().positionMs)
        } finally {
            controller.close()
        }
    }

    @Test
    fun explicitPlayOnPausedTrackPublishesPlayIntentAndWaitsForBarrier() = runTest {
        val canonical = "https://www.youtube.com/watch?v=local-resume"
        val repository = FakeRoomRepository()
        val player = FakeRoomPlayer(
            LocalPlaybackSnapshot(
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Local resume",
                artist = "Artist",
                playWhenReady = false,
                isPlaying = false,
                isReady = true,
                positionMs = 25_000L,
                durationMs = 100_000L
            )
        )
        val controller = newController(repository, player)

        try {
            controller.attach("room-local-resume")
            advanceTimeBy(100L)
            runCurrent()
            assertEquals(RoomActionType.TRACK, repository.published.single().actionType)
            assertFalse(repository.published.single().isPlaying)

            player.emitIntent(RoomPlayerIntent.Play)
            runCurrent()
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(2, repository.published.size)
            assertEquals(RoomActionType.PLAY, repository.published.last().actionType)
            assertEquals(2L, controller.state.value.playback?.revision)
            assertTrue("PLAY intent must remain true even though the local player is held paused", repository.published.last().isPlaying)
            assertEquals(1, repository.readinessCalls)
            assertFalse(player.snapshot().playWhenReady)
        } finally {
            controller.close()
        }
    }

    @Test
    fun localTrackFallsBackToSharedTimelineWhenPeerNeverBecomesReady() = runTest {
        val canonical = "https://www.youtube.com/watch?v=barrier-fallback"
        val repository = FakeRoomRepository()
        val player = FakeRoomPlayer(
            LocalPlaybackSnapshot(
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Fallback track",
                artist = "Artist",
                playWhenReady = true,
                isPlaying = true,
                isReady = true,
                positionMs = 0L,
                durationMs = 90_000L
            )
        )
        val clock = FakeRoomClock()
        val controller = newController(repository, player, clock = clock)

        try {
            controller.attach("room-barrier-fallback")
            runCurrent()
            assertFalse(player.snapshot().playWhenReady)
            assertEquals(1, repository.readinessCalls)

            clock.advance(46_100L)
            advanceTimeBy(100L)
            runCurrent()

            assertTrue("the ready device must not remain paused forever", player.snapshot().playWhenReady)
            assertTrue(player.snapshot().isPlaying)
            assertEquals(46_100L, player.snapshot().positionMs)
            assertEquals(2, repository.readinessCalls)
            assertEquals(1L, controller.state.value.playback?.revision)
            assertTrue(
                controller.diagnostics.value.events.any { it.contains("barrier.started_without_peer") }
            )

            // A peer that becomes ready at the edge of the server expiry window can
            // still deliver the shared anchor after local fallback has begun.
            clock.advance(1_500L)
            player.setSnapshot(player.snapshot().copy(positionMs = 47_600L))
            repository.emit(
                RoomRealtimeSignal.StartScheduled(
                    revision = 1L,
                    sourceId = canonical,
                    startAt = "1970-01-01T00:00:57.601Z"
                )
            )
            runCurrent()
            assertEquals(
                "late shared anchor must preserve the command-time playback timeline",
                47_600L,
                player.snapshot().positionMs
            )
            assertTrue(controller.diagnostics.value.events.any {
                it.contains("barrier.late_shared_anchor")
            })
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
        repository.scheduledStartAt = "1970-01-01T00:00:11.501Z"
        val player = FakeRoomPlayer()
        val clock = FakeRoomClock()
        val controller = newController(repository, player, clock = clock)

        try {
            controller.attach("room-2")
            runCurrent()
            advanceTimeBy(50L)
            runCurrent()

            assertEquals(canonical, player.snapshot().canonicalSourceUrl)
            assertTrue(player.snapshot().isReady)
            assertFalse("remote track must remain paused before the common start", player.snapshot().playWhenReady)
            assertTrue(repository.published.isEmpty())
            assertEquals(7L, controller.state.value.playback?.revision)

            clock.advance(1_501L)
            advanceTimeBy(250L)
            runCurrent()

            assertTrue(player.snapshot().playWhenReady)
            assertTrue(player.snapshot().isPlaying)
        } finally {
            controller.close()
        }
    }

    @Test
    fun playOnAnAlreadyPreparedPausedTrackUsesTheSharedStartBarrier() = runTest {
        val canonical = "https://www.youtube.com/watch?v=resume-barrier"
        val repository = FakeRoomRepository(
            initialSnapshot = RoomPlaybackSnapshot(
                roomId = "room-resume",
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Resume track",
                artist = "Artist",
                isPlaying = true,
                positionMs = 30_000L,
                durationMs = 120_000L,
                revision = 9L,
                updatedBy = "other-device",
                updatedAt = "1970-01-01T00:00:10.001Z",
                actionId = "resume-action",
                actionType = RoomActionType.PLAY,
                originDeviceId = "other-device",
                commandAt = "1970-01-01T00:00:10.001Z"
            )
        )
        repository.scheduledStartAt = "1970-01-01T00:00:11.501Z"
        val player = FakeRoomPlayer(
            LocalPlaybackSnapshot(
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Resume track",
                artist = "Artist",
                playWhenReady = false,
                isPlaying = false,
                isReady = true,
                positionMs = 30_000L,
                durationMs = 120_000L
            )
        )
        val clock = FakeRoomClock()
        val controller = newController(repository, player, clock = clock)

        try {
            controller.attach("room-resume")
            runCurrent()
            assertFalse("resume must wait for the shared anchor", player.snapshot().playWhenReady)
            assertEquals(1, repository.readinessCalls)
            assertTrue(repository.published.isEmpty())

            clock.advance(1_501L)
            advanceTimeBy(250L)
            runCurrent()

            assertTrue(player.snapshot().playWhenReady)
            assertTrue(player.snapshot().isPlaying)
            assertEquals(31_501L, player.snapshot().positionMs)
            assertTrue(repository.published.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun sameCanonicalTrackWithIdleLocalPlayerResolvesFreshStream() = runTest {
        val canonical = "https://www.youtube.com/watch?v=expired-local-stream"
        val repository = FakeRoomRepository(
            initialSnapshot = RoomPlaybackSnapshot(
                roomId = "room-expired",
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Shared track",
                artist = "Artist",
                isPlaying = true,
                positionMs = 4_000L,
                durationMs = 120_000L,
                revision = 3L,
                updatedBy = "other-device",
                updatedAt = "2026-11-06T00:00:00.000Z",
                actionId = "remote-track",
                actionType = RoomActionType.TRACK,
                originDeviceId = "other-device",
                commandAt = "2026-11-06T00:00:00.000Z"
            )
        )
        val player = FakeRoomPlayer(
            LocalPlaybackSnapshot(
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Shared track",
                artist = "Artist",
                playWhenReady = false,
                isPlaying = false,
                isBuffering = false,
                isReady = false,
                positionMs = 4_000L,
                durationMs = 120_000L
            )
        )
        val resolver = FakeRoomMediaResolver()
        val controller = newController(repository, player, resolver)

        try {
            controller.attach("room-expired")
            runCurrent()
            advanceTimeBy(50L)
            runCurrent()

            assertEquals(1, resolver.resolveCount)
            assertTrue(player.snapshot().isReady)
            assertFalse(player.snapshot().playWhenReady)
            assertTrue(repository.published.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun sameDeviceTrackWithIdleLocalPlayerResolvesFreshStream() = runTest {
        val canonical = "https://www.youtube.com/watch?v=same-device-expired-stream"
        val repository = FakeRoomRepository(
            initialSnapshot = RoomPlaybackSnapshot(
                roomId = "room-own-expired",
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Local shared track",
                artist = "Artist",
                isPlaying = false,
                positionMs = 7_000L,
                durationMs = 100_000L,
                revision = 5L,
                updatedBy = "device-1",
                updatedAt = "2026-11-06T00:00:00.000Z",
                actionId = "own-track-action",
                actionType = RoomActionType.TRACK,
                originDeviceId = "device-1",
                commandAt = "2026-11-06T00:00:00.000Z"
            )
        )
        val player = FakeRoomPlayer(
            LocalPlaybackSnapshot(
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Local shared track",
                artist = "Artist",
                isPlaying = false,
                isBuffering = false,
                isReady = false,
                positionMs = 7_000L,
                durationMs = 100_000L
            )
        )
        val resolver = FakeRoomMediaResolver()
        val controller = newController(repository, player, resolver)

        try {
            controller.attach("room-own-expired")
            runCurrent()
            advanceTimeBy(50L)
            runCurrent()

            assertEquals(1, resolver.resolveCount)
            assertTrue(player.snapshot().isReady)
            assertFalse(player.snapshot().isPlaying)
            assertTrue(repository.published.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun sameDeviceTrackAlreadyBufferingIsNotRestarted() = runTest {
        val canonical = "https://www.youtube.com/watch?v=same-device-buffering"
        val repository = FakeRoomRepository(
            initialSnapshot = RoomPlaybackSnapshot(
                roomId = "room-own-buffering",
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Buffering track",
                artist = "Artist",
                isPlaying = true,
                positionMs = 2_000L,
                durationMs = 100_000L,
                revision = 6L,
                updatedBy = "device-1",
                updatedAt = "2026-11-06T00:00:00.000Z",
                actionId = "own-buffering-action",
                actionType = RoomActionType.TRACK,
                originDeviceId = "device-1",
                commandAt = "2026-11-06T00:00:00.000Z"
            )
        )
        val player = FakeRoomPlayer(
            LocalPlaybackSnapshot(
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Buffering track",
                artist = "Artist",
                isPlaying = false,
                isBuffering = true,
                isReady = false,
                positionMs = 2_000L,
                durationMs = 100_000L
            )
        )
        val resolver = FakeRoomMediaResolver()
        val controller = newController(repository, player, resolver)

        try {
            controller.attach("room-own-buffering")
            runCurrent()
            advanceTimeBy(50L)
            runCurrent()

            assertEquals(0, resolver.resolveCount)
            assertTrue(player.snapshot().isBuffering)
            assertTrue(repository.published.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun failedRemoteStreamResolutionWaitsForBackoffBeforeRetrying() = runTest {
        val canonical = "https://www.youtube.com/watch?v=retry-after-failure"
        val repository = FakeRoomRepository(
            initialSnapshot = RoomPlaybackSnapshot(
                roomId = "room-retry",
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Retry track",
                artist = "Artist",
                isPlaying = true,
                positionMs = 1_000L,
                durationMs = 90_000L,
                revision = 8L,
                updatedBy = "other-device",
                updatedAt = "2026-11-06T00:00:00.000Z",
                actionId = "retry-action",
                actionType = RoomActionType.TRACK,
                originDeviceId = "other-device",
                commandAt = "2026-11-06T00:00:00.000Z"
            )
        )
        val player = FakeRoomPlayer()
        val resolver = FakeRoomMediaResolver(failuresBeforeSuccess = 1)
        val clock = FakeRoomClock()
        val controller = newController(repository, player, resolver, clock)

        try {
            controller.attach("room-retry")
            runCurrent()
            assertEquals(1, resolver.resolveCount)

            clock.advance(999L)
            repository.emit(RoomRealtimeSignal.PlaybackInvalidated)
            runCurrent()
            assertEquals(1, resolver.resolveCount)

            clock.advance(1L)
            repository.emit(RoomRealtimeSignal.PlaybackInvalidated)
            runCurrent()

            assertEquals(2, resolver.resolveCount)
            assertTrue(player.snapshot().isReady)
            assertFalse("successfully resolved track remains paused until the start barrier", player.snapshot().playWhenReady)
            assertTrue(repository.published.isEmpty())
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

    private fun TestScope.newController(
        repository: FakeRoomRepository,
        player: FakeRoomPlayer,
        mediaResolver: FakeRoomMediaResolver = FakeRoomMediaResolver(),
        clock: FakeRoomClock = FakeRoomClock()
    ) = ServiceRoomController(
        parentScope = this,
        repository = repository,
        player = player,
        clock = clock,
        mediaResolver = mediaResolver
    )

    private class FakeRoomRepository(
        initialSnapshot: RoomPlaybackSnapshot? = null
    ) : RoomRepository {
        val published = mutableListOf<RoomCommand>()
        val signals = MutableSharedFlow<RoomRealtimeSignal>(extraBufferCapacity = 16)
        var snapshot: RoomPlaybackSnapshot? = initialSnapshot
        var leaveCount = 0
        var scheduledStartAt: String? = null
        var readinessCalls = 0

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

        override suspend fun markPlaybackReady(
            roomId: String,
            revision: Long,
            sourceId: String
        ): RoomPlaybackReadyResult {
            readinessCalls += 1
            val current = checkNotNull(snapshot) { "readiness requires an authoritative playback snapshot" }
            check(current.roomId == roomId && current.revision == revision) {
                "readiness revision must match the authoritative snapshot"
            }
            check(current.canonicalSourceUrl == sourceId &&
                current.actionType in setOf(RoomActionType.TRACK, RoomActionType.PLAY) &&
                current.isPlaying
            ) {
                "only an active TRACK or PLAY revision can enter the shared start barrier"
            }
            return RoomPlaybackReadyResult(
                startAt = scheduledStartAt,
                activeMembers = 2,
                readyMembers = if (scheduledStartAt == null) 1 else 2
            )
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
                playWhenReady = false,
                isPlaying = false,
                positionMs = 0L
            )
        }

        override fun play() {
            mutablePlayback.value = mutablePlayback.value.copy(
                playWhenReady = true,
                isPlaying = true,
                isBuffering = false,
                isReady = true
            )
        }

        override fun pause() {
            mutablePlayback.value = mutablePlayback.value.copy(playWhenReady = false, isPlaying = false)
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

        fun setSnapshot(snapshot: LocalPlaybackSnapshot) {
            mutablePlayback.value = snapshot
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

        fun advance(milliseconds: Long) {
            elapsedMs += milliseconds.coerceAtLeast(0L)
        }
    }

    private class FakeRoomMediaResolver(
        private val failuresBeforeSuccess: Int = 0
    ) : RoomMediaResolver {
        var resolveCount = 0

        override suspend fun resolve(
            sourceId: String,
            canonicalSourceUrl: String
        ): ResolvedAudio {
            resolveCount += 1
            if (resolveCount <= failuresBeforeSuccess) {
                throw IOException("Temporary stream resolution failure.")
            }
            return ResolvedAudio(
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
}
