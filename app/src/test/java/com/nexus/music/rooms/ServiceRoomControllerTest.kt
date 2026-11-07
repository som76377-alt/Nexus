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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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

    // SPEC: S2, S8
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

    // SPEC: S2, S8
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

    // SPEC: S3, S8
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

    // SPEC: S2, S3
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
                RoomPlayerIntent.SelectTrack(
                    sourceId = second,
                    canonicalSourceUrl = second,
                    title = "Second track",
                    artist = null,
                    shouldPlay = true
                )
            )
            runCurrent()
            advanceTimeBy(250L)
            runCurrent()

            assertEquals(second, repository.published.last().sourceId)
            assertEquals(RoomActionType.TRACK, repository.published.last().actionType)
            assertTrue("skipping while playing must keep the new track playing", repository.published.last().isPlaying)

            player.setSnapshot(
                player.snapshot().copy(
                    sourceId = third,
                    canonicalSourceUrl = third,
                    title = "Third track",
                    positionMs = 0L
                )
            )
            player.emitIntent(
                RoomPlayerIntent.SelectTrack(
                    sourceId = third,
                    canonicalSourceUrl = third,
                    title = "Third track",
                    artist = null,
                    shouldPlay = true
                )
            )
            runCurrent()
            advanceTimeBy(250L)
            runCurrent()

            assertEquals(third, repository.published.last().sourceId)
            assertEquals(RoomActionType.TRACK, repository.published.last().actionType)
            assertTrue("previous while playing must keep the selected track playing", repository.published.last().isPlaying)
            assertEquals(3, repository.published.size)
        } finally {
            controller.close()
        }
    }

    // SPEC: S2, S8
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
        val clock = FakeRoomClock(failuresBeforeSuccess = 1)
        val controller = newController(repository, player, clock = clock)

        try {
            controller.attach("room-barrier-local")
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()

            assertTrue("a failed attach probe must be retried before playback scheduling", clock.calibrationCalls >= 2)
            assertTrue(clock.isCalibrated())
            assertEquals(1, repository.published.size)
            assertTrue(repository.published.single().isPlaying)
            assertTrue(repository.readinessCalls >= 1)
            assertFalse("track must wait for the scheduled start", player.snapshot().playWhenReady)
            assertEquals("barrier progress is not an error", null, controller.state.value.lastError)
            assertEquals("Preparing synchronized start…", controller.state.value.statusMessage)

            // A MediaSession Play press while the new track is waiting must not
            // cancel the barrier or publish a plain PLAY revision.
            player.emitIntent(RoomPlayerIntent.Play)
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()
            assertEquals(1, repository.published.size)
            assertEquals(1, repository.readinessCalls)
            assertEquals("Preparing synchronized start…", controller.state.value.statusMessage)
            assertFalse(player.snapshot().playWhenReady)

            // No local track or seek change means the player wrapper emits no room intent.
            runCurrent()
            assertEquals(1, repository.published.size)
            assertEquals(1, repository.readinessCalls)
            assertEquals("Preparing synchronized start…", controller.state.value.statusMessage)

            clock.advance(1_501L)
            advanceTimeBy(250L)
            runCurrent()

            assertTrue(player.snapshot().playWhenReady)
            assertTrue(player.snapshot().isPlaying)
            assertEquals("only time after start_at advances the staged track", 2L, player.snapshot().positionMs)
            assertEquals(1L, controller.state.value.playback?.revision)
            assertEquals(null, controller.state.value.statusMessage)

            repository.emit(
                RoomRealtimeSignal.StartScheduled(
                    revision = 1L,
                    sourceId = canonical,
                    startAt = repository.scheduledStartAt.orEmpty()
                )
            )
            runCurrent()
            assertEquals(
                "a duplicate scheduled START must not re-run the barrier or seek",
                2L,
                player.snapshot().positionMs
            )
            assertTrue(controller.diagnostics.value.events.any {
                it.contains("barrier.duplicate_start_ignored")
            })

            // Drift correction must project from the exact position at the scheduled
            // anchor, not combine the old command position with the new anchor time.
            clock.advance(1_000L)
            player.setSnapshot(player.snapshot().copy(positionMs = 1_002L))
            advanceTimeBy(1_000L)
            runCurrent()
            clock.advance(1_000L)
            player.setSnapshot(player.snapshot().copy(positionMs = 2_002L))
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(2_002L, player.snapshot().positionMs)
        } finally {
            controller.close()
        }
    }

    // SPEC: S1, S8
    @Test
    fun explicitPlayOnPausedTrackResumesLocallyWithoutWaitingForBarrier() = runTest {
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

            player.setSnapshot(player.snapshot().copy(playWhenReady = true, isPlaying = true))
            player.emitIntent(RoomPlayerIntent.Play)
            runCurrent()
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(2, repository.published.size)
            assertEquals(RoomActionType.PLAY, repository.published.last().actionType)
            assertEquals(2L, controller.state.value.playback?.revision)
            assertTrue("PLAY intent must be published", repository.published.last().isPlaying)
            assertEquals("resume must not call READY", 0, repository.readinessCalls)
            assertTrue("local audio resumes immediately", player.snapshot().playWhenReady)
            assertTrue(player.snapshot().isPlaying)
            assertEquals("resume retains the paused position", 25_000L, player.snapshot().positionMs)
        } finally {
            controller.close()
        }
    }

    // SPEC: S3
    @Test
    fun seekPublishesRequestedPositionBeforePlayerSnapshotAdvances() = runTest {
        val canonical = "https://www.youtube.com/watch?v=seek-intent"
        val repository = FakeRoomRepository()
        val player = FakeRoomPlayer(
            LocalPlaybackSnapshot(
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Seek intent",
                playWhenReady = false,
                isPlaying = false,
                isReady = true,
                positionMs = 25_000L,
                durationMs = 120_000L
            )
        )
        val controller = newController(repository, player)

        try {
            controller.attach("room-seek-intent")
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()
            assertEquals(RoomActionType.TRACK, repository.published.single().actionType)

            // The MediaSession wrapper records SeekTo before delegating to ExoPlayer,
            // so the fresh snapshot can still contain the old position here.
            listOf(30_000L, 35_000L, 45_000L).forEach {
                player.emitIntent(RoomPlayerIntent.SeekTo(it))
            }
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()

            assertEquals(2, repository.published.size)
            assertEquals(1, repository.published.count { it.actionType == RoomActionType.SEEK })
            assertEquals(RoomActionType.SEEK, repository.published.last().actionType)
            assertEquals(45_000L, repository.published.last().positionMs)
        } finally {
            controller.close()
        }
    }


    // SPEC: S3, S7, S8
    @Test
    fun failedPublishRetriesWithBackoffFromTheSinglePublishJob() = runTest {
        val canonical = "https://www.youtube.com/watch?v=publish-retry"
        val repository = FakeRoomRepository().apply {
            publishGate = CompletableDeferred<Unit>().apply {
                completeExceptionally(IOException("Temporary publish failure"))
            }
        }
        val player = FakeRoomPlayer(LocalPlaybackSnapshot(
            sourceId = canonical, canonicalSourceUrl = canonical, title = "Retry", isReady = true
        ))
        val controller = newController(repository, player)
        try {
            controller.attach("room-publish-retry")
            runCurrent()
            advanceTimeBy(100L)
            runCurrent()
            assertTrue(repository.published.isEmpty())
            advanceTimeBy(499L)
            runCurrent()
            assertTrue(repository.published.isEmpty())
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(1, repository.published.size)
        } finally {
            controller.close()
        }
    }

    // SPEC: S2, S8
    @Test
    fun playDuringInFlightPausedTrackPublishIsRepublishedAsTrackPlay() = runTest {
        val canonical = "https://www.youtube.com/watch?v=inflight-track-play"
        val repository = FakeRoomRepository()
        val player = FakeRoomPlayer()
        val controller = newController(repository, player)
        val publishGate = CompletableDeferred<Unit>()

        try {
            controller.attach("room-inflight-track")
            runCurrent()
            repository.publishGate = publishGate
            player.setSnapshot(
                LocalPlaybackSnapshot(
                    sourceId = canonical,
                    canonicalSourceUrl = canonical,
                    title = "In-flight track",
                    playWhenReady = false,
                    isPlaying = false,
                    isReady = true,
                    durationMs = 90_000L
                )
            )
            player.emitIntent(
                RoomPlayerIntent.SelectTrack(
                    sourceId = canonical,
                    canonicalSourceUrl = canonical,
                    title = "In-flight track",
                    artist = null,
                    shouldPlay = false
                )
            )
            runCurrent()
            advanceTimeBy(100L)
            runCurrent()

            // The paused TRACK RPC is in flight. Play must update the pending action,
            // not get lost behind the active publish job.
            player.setSnapshot(player.snapshot().copy(playWhenReady = true, isPlaying = true))
            player.emitIntent(RoomPlayerIntent.Play)
            runCurrent()
            assertFalse(player.snapshot().playWhenReady)

            publishGate.complete(Unit)
            runCurrent()
            advanceTimeBy(60L)
            runCurrent()

            assertEquals(2, repository.published.size)
            assertEquals(RoomActionType.TRACK, repository.published[0].actionType)
            assertFalse(repository.published[0].isPlaying)
            assertEquals(RoomActionType.TRACK, repository.published[1].actionType)
            assertTrue(repository.published[1].isPlaying)
            assertTrue("changed payloads need a new idempotency key",
                repository.published[0].actionId != repository.published[1].actionId)
            assertEquals(1, repository.readinessCalls)
            assertFalse(player.snapshot().playWhenReady)
        } finally {
            controller.close()
        }
    }


    // SPEC: S2
    @Test
    fun remoteTrackReadinessUsesDatabaseSourceIdNotCanonicalUrl() = runTest {
        val videoId = "browser-video-id"
        val canonical = "https://www.youtube.com/watch?v=$videoId"
        val repository = FakeRoomRepository(
            initialSnapshot = RoomPlaybackSnapshot(
                roomId = "room-browser-source",
                sourceId = videoId,
                canonicalSourceUrl = canonical,
                title = "Browser track",
                artist = "Browser artist",
                isPlaying = true,
                positionMs = 0L,
                durationMs = 120_000L,
                revision = 7L,
                updatedBy = "device-2",
                updatedAt = "1970-01-01T00:00:10.000Z",
                actionId = "browser-action",
                actionType = RoomActionType.TRACK,
                originDeviceId = "device-2",
                commandAt = "1970-01-01T00:00:10.000Z"
            )
        )
        repository.scheduledStartAt = "1970-01-01T00:00:12.000Z"
        val player = FakeRoomPlayer()
        val clock = FakeRoomClock()
        val controller = newController(repository, player, clock = clock)

        try {
            controller.attach("room-browser-source")
            runCurrent()

            assertEquals(1, repository.readinessCalls)
            assertEquals(listOf(videoId), repository.readinessSourceIds)
            assertEquals(canonical, player.snapshot().canonicalSourceUrl)
            assertEquals("Preparing synchronized start…", controller.state.value.statusMessage)
            assertFalse(player.snapshot().playWhenReady)

            // The scheduled-start payload carries source_id, not source_url.
            clock.advance(2_000L)
            advanceTimeBy(2_000L)
            runCurrent()
            assertTrue(player.snapshot().playWhenReady)
        } finally {
            controller.close()
        }
    }

    // SPEC: S2, S8
    @Test
    fun skipDuringSharedStartPreservesDesiredPlayingState() = runTest {
        val first = "https://www.youtube.com/watch?v=first-barrier-track"
        val second = "https://www.youtube.com/watch?v=second-barrier-track"
        val repository = FakeRoomRepository()
        val player = FakeRoomPlayer()
        val controller = newController(repository, player)

        try {
            controller.attach("room-skip-barrier")
            runCurrent()
            player.setSnapshot(
                LocalPlaybackSnapshot(
                    sourceId = first,
                    canonicalSourceUrl = first,
                    title = "First track",
                    playWhenReady = false,
                    isPlaying = false,
                    isReady = true,
                    durationMs = 90_000L
                )
            )
            player.emitIntent(
                RoomPlayerIntent.SelectTrack(
                    sourceId = first,
                    canonicalSourceUrl = first,
                    title = "First track",
                    artist = null,
                    shouldPlay = true
                )
            )
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()
            assertEquals(1, repository.readinessCalls)
            assertTrue(controller.playbackPhase().shouldPlay)

            player.setSnapshot(
                LocalPlaybackSnapshot(
                    sourceId = second,
                    canonicalSourceUrl = second,
                    title = "Second track",
                    playWhenReady = false,
                    isPlaying = false,
                    isReady = true,
                    durationMs = 90_000L
                )
            )
            player.emitIntent(
                RoomPlayerIntent.SelectTrack(
                    sourceId = second,
                    canonicalSourceUrl = second,
                    title = "Second track",
                    artist = null,
                    shouldPlay = controller.playbackPhase().shouldPlay
                )
            )
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()

            assertEquals(2, repository.published.size)
            assertEquals(second, repository.published.last().canonicalSourceUrl)
            assertEquals(RoomActionType.TRACK, repository.published.last().actionType)
            assertTrue(repository.published.last().isPlaying)
            assertEquals(2, repository.readinessCalls)
        } finally {
            controller.close()
        }
    }

    // SPEC: S2, S3
    @Test
    fun playImmediatelyAfterPausedTrackSelectionKeepsTrackBarrier() = runTest {
        val canonical = "https://www.youtube.com/watch?v=paused-selection-play"
        val repository = FakeRoomRepository()
        val player = FakeRoomPlayer()
        val controller = newController(repository, player)

        try {
            controller.attach("room-paused-selection")
            runCurrent()

            // Select a prepared track, then press Play before the debounce can publish
            // the TRACK command. The command must retain TRACK semantics and the shared
            // start barrier because the peer may not have prepared this source yet.
            player.setSnapshot(
                LocalPlaybackSnapshot(
                    sourceId = canonical,
                    canonicalSourceUrl = canonical,
                    title = "Paused selection",
                    artist = "Artist",
                    playWhenReady = false,
                    isPlaying = false,
                    isReady = true,
                    positionMs = 0L,
                    durationMs = 90_000L
                )
            )
            player.emitIntent(
                RoomPlayerIntent.SelectTrack(
                    sourceId = canonical,
                    canonicalSourceUrl = canonical,
                    title = "Paused selection",
                    artist = "Artist",
                    shouldPlay = false
                )
            )
            player.setSnapshot(player.snapshot().copy(playWhenReady = true, isPlaying = true))
            player.emitIntent(RoomPlayerIntent.Play)
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()

            assertEquals(1, repository.published.size)
            assertEquals(RoomActionType.TRACK, repository.published.single().actionType)
            assertTrue(repository.published.single().isPlaying)
            assertEquals("new source must enter the shared READY barrier", 1, repository.readinessCalls)
            assertFalse("local audio remains staged until START", player.snapshot().playWhenReady)
            assertFalse(player.snapshot().isPlaying)
        } finally {
            controller.close()
        }
    }

    // SPEC: S2, S7, S8
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

            // S2: peer readiness is bounded to three seconds; local audio starts at the deadline.
            clock.advance(2_999L)
            advanceTimeBy(2_999L)
            runCurrent()
            assertFalse("do not start before the S2 deadline", player.snapshot().playWhenReady)

            clock.advance(1L)
            repository.serverNowMs = clock.estimatedServerTimeMs()
            advanceTimeBy(1L)
            runCurrent()

            assertTrue("ready local audio must start by the three-second S2 deadline", player.snapshot().playWhenReady)
            assertTrue(player.snapshot().isPlaying)

            // Allow the barrier's final readiness check to observe the already-published RECOVERY.
            clock.advance(200L)
            advanceTimeBy(200L)
            runCurrent()
            assertEquals("fallback starts from the new track's base position", 0L, player.snapshot().positionMs)
            assertEquals(2, repository.readinessCalls)
            assertEquals(RoomActionType.RECOVERY, repository.published.last().actionType)
            assertEquals(0L, repository.published.last().positionMs)
            assertEquals(2L, controller.state.value.playback?.revision)
            assertTrue(controller.diagnostics.value.events.any {
                it.contains("barrier.started_without_peer")
            })
            assertTrue(controller.diagnostics.value.events.any {
                it.contains("barrier.recovery_published")
            })

            // A delayed START for the superseded TRACK revision must not rewind the
            // already-playing local fallback; RECOVERY is now the shared timeline.
            player.setSnapshot(player.snapshot().copy(positionMs = 1_000L))
            repository.emit(
                RoomRealtimeSignal.StartScheduled(
                    revision = 1L,
                    sourceId = canonical,
                    startAt = "1970-01-01T00:00:57.601Z"
                )
            )
            runCurrent()
            assertEquals(1_000L, player.snapshot().positionMs)
        } finally {
            controller.close()
        }
    }

    // SPEC: S3, S5
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

    // SPEC: S1, S8
    @Test
    fun playOnAnAlreadyPreparedPausedTrackResumesImmediatelyWithoutBarrier() = runTest {
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
            assertTrue("remote PLAY resumes the prepared item immediately", player.snapshot().playWhenReady)
            assertTrue(player.snapshot().isPlaying)
            assertEquals(30_000L, player.snapshot().positionMs)
            assertEquals("resume does not call READY", 0, repository.readinessCalls)
            assertTrue(repository.published.isEmpty())
        } finally {
            controller.close()
        }
    }

    // SPEC: S2, S7, S8
    @Test
    fun recoveryCommandLetsLatePeerCatchUpWithoutAnotherReadinessBarrier() = runTest {
        val canonical = "https://www.youtube.com/watch?v=recovery-catch-up"
        val repository = FakeRoomRepository(
            initialSnapshot = RoomPlaybackSnapshot(
                roomId = "room-recovery",
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Recovery track",
                artist = "Artist",
                isPlaying = true,
                positionMs = 2_000L,
                durationMs = 120_000L,
                revision = 4L,
                updatedBy = "device-1",
                updatedAt = "1970-01-01T00:00:10.000Z",
                actionId = "recovery-action",
                actionType = RoomActionType.RECOVERY,
                originDeviceId = "device-1",
                commandAt = "1970-01-01T00:00:10.000Z"
            )
        )
        val player = FakeRoomPlayer()
        val clock = FakeRoomClock()
        val controller = newController(repository, player, clock = clock)

        try {
            controller.attach("room-recovery")
            runCurrent()
            advanceTimeBy(50L)
            runCurrent()

            assertEquals(canonical, player.snapshot().canonicalSourceUrl)
            assertTrue(player.snapshot().isReady)
            assertTrue(player.snapshot().playWhenReady)
            assertTrue(player.snapshot().isPlaying)
            assertEquals(2_001L, player.snapshot().positionMs)
            assertEquals("RECOVERY must not enter READY/START", 0, repository.readinessCalls)
        } finally {
            controller.close()
        }
    }

    // SPEC: S2, S8
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

    // SPEC: S2, S8
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

    // SPEC: S2, S8
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

    // SPEC: S7, S8
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

    // SPEC: S5, S8
    @Test
    fun s5SystemPauseIsLocalOnlyAndDriftCorrectionStaysSuspended() = runTest {
        val canonical = "https://www.youtube.com/watch?v=system-pause-local"
        val remote = RoomPlaybackSnapshot(
            roomId = "room-system-pause",
            sourceId = canonical,
            canonicalSourceUrl = canonical,
            title = "System pause",
            artist = "Artist",
            isPlaying = true,
            positionMs = 10_000L,
            durationMs = 90_000L,
            revision = 4L,
            updatedBy = "other-device",
            updatedAt = "1970-01-01T00:00:10.000Z",
            actionId = "remote-play",
            actionType = RoomActionType.PLAY,
            originDeviceId = "other-device",
            commandAt = "1970-01-01T00:00:10.000Z"
        )
        val repository = FakeRoomRepository(initialSnapshot = remote)
        val player = FakeRoomPlayer(
            LocalPlaybackSnapshot(
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "System pause",
                artist = "Artist",
                playWhenReady = true,
                isPlaying = true,
                isReady = true,
                positionMs = 10_000L,
                durationMs = 90_000L
            )
        )
        val clock = FakeRoomClock()
        val controller = newController(repository, player, clock = clock)

        try {
            controller.attach("room-system-pause")
            runCurrent()
            assertTrue(repository.published.isEmpty())

            player.emitIntent(RoomPlayerIntent.SystemPause)
            runCurrent()
            val seeksBeforeInterruption = player.seekCalls
            val speedChangesBeforeInterruption = player.speedChanges
            // Model transient focus suppression: desired play remains true, and an
            // out-of-date player callback must not trigger room drift correction.
            player.setSnapshot(
                player.snapshot().copy(
                    playWhenReady = true,
                    isPlaying = true,
                    positionMs = 50_000L
                )
            )
            clock.advance(5_000L)
            advanceTimeBy(5_000L)
            runCurrent()

            assertTrue("Android system pause must not publish a room PAUSE", repository.published.isEmpty())
            assertEquals("system-paused drift must not seek", seeksBeforeInterruption, player.seekCalls)
            assertEquals("system-paused drift must not change playback speed", speedChangesBeforeInterruption, player.speedChanges)
            assertEquals("system-paused local timeline is left untouched", 50_000L, player.snapshot().positionMs)
            assertTrue(controller.diagnostics.value.events.any { it.contains("local.system_pause") })
        } finally {
            controller.close()
        }
    }

    // SPEC: S5, S8
    @Test
    fun s5ExplicitPlayAfterSystemPauseRejoinsPlayingRoomTimelineWithoutPublishing() = runTest {
        val canonical = "https://www.youtube.com/watch?v=system-pause-rejoin"
        val remote = RoomPlaybackSnapshot(
            roomId = "room-system-rejoin",
            sourceId = canonical,
            canonicalSourceUrl = canonical,
            title = "Rejoin track",
            artist = "Artist",
            isPlaying = true,
            positionMs = 10_000L,
            durationMs = 90_000L,
            revision = 7L,
            updatedBy = "other-device",
            updatedAt = "1970-01-01T00:00:10.000Z",
            actionId = "remote-play",
            actionType = RoomActionType.PLAY,
            originDeviceId = "other-device",
            commandAt = "1970-01-01T00:00:10.000Z"
        )
        val repository = FakeRoomRepository(initialSnapshot = remote)
        val player = FakeRoomPlayer(
            LocalPlaybackSnapshot(
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Rejoin track",
                artist = "Artist",
                playWhenReady = true,
                isPlaying = true,
                isReady = true,
                positionMs = 10_000L,
                durationMs = 90_000L
            )
        )
        val clock = FakeRoomClock()
        val controller = newController(repository, player, clock = clock)

        try {
            controller.attach("room-system-rejoin")
            runCurrent()
            player.pause()
            player.emitIntent(RoomPlayerIntent.SystemPause)
            runCurrent()

            clock.advance(1_500L)
            player.emitIntent(RoomPlayerIntent.Play)
            runCurrent()

            assertTrue("explicit Play rejoins the room timeline locally", player.snapshot().playWhenReady)
            assertTrue(player.snapshot().isPlaying)
            assertEquals("resume seeks to the current shared position including attach calibration", 11_501L, player.snapshot().positionMs)
            assertTrue("rejoining must not publish a room PLAY", repository.published.isEmpty())
            assertTrue(controller.diagnostics.value.events.any {
                it.contains("local.system_pause_rejoined")
            })
        } finally {
            controller.close()
        }
    }

    // SPEC: S5, S8
    @Test
    fun s5TransientAudioFocusReleaseRejoinsRoomTimelineWithoutPublishing() = runTest {
        val canonical = "https://www.youtube.com/watch?v=focus-release-rejoin"
        val remote = RoomPlaybackSnapshot(
            roomId = "room-focus-release",
            sourceId = canonical,
            canonicalSourceUrl = canonical,
            title = "Focus release",
            artist = "Artist",
            isPlaying = true,
            positionMs = 20_000L,
            durationMs = 90_000L,
            revision = 9L,
            updatedBy = "other-device",
            updatedAt = "1970-01-01T00:00:10.000Z",
            actionId = "remote-play",
            actionType = RoomActionType.PLAY,
            originDeviceId = "other-device",
            commandAt = "1970-01-01T00:00:10.000Z"
        )
        val repository = FakeRoomRepository(initialSnapshot = remote)
        val player = FakeRoomPlayer(
            LocalPlaybackSnapshot(
                sourceId = canonical,
                canonicalSourceUrl = canonical,
                title = "Focus release",
                artist = "Artist",
                playWhenReady = true,
                isPlaying = true,
                isReady = true,
                positionMs = 20_000L,
                durationMs = 90_000L
            )
        )
        val clock = FakeRoomClock()
        val controller = newController(repository, player, clock = clock)

        try {
            controller.attach("room-focus-release")
            runCurrent()
            player.emitIntent(RoomPlayerIntent.SystemPause)
            runCurrent()
            clock.advance(2_000L)
            player.emitIntent(RoomPlayerIntent.SystemResume)
            runCurrent()

            assertTrue("transient focus regain resumes local playback", player.snapshot().playWhenReady)
            assertTrue(player.snapshot().isPlaying)
            assertEquals("automatic release rejoins the current room position including attach calibration", 22_001L, player.snapshot().positionMs)
            assertTrue("automatic interruption release must not publish", repository.published.isEmpty())
        } finally {
            controller.close()
        }
    }

    // SPEC: S7
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

    // SPEC: S7
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
        var scheduledStartAt: String? = null
        var readinessCalls = 0
        val readinessSourceIds = mutableListOf<String>()
        var publishGate: CompletableDeferred<Unit>? = null
        private val actionRevisions = mutableMapOf<String, Long>()
        var serverNowMs: Long = 10_000L

        override suspend fun currentUserId(): String = "device-1"

        override suspend fun fetchPlaybackSnapshot(roomId: String): RoomPlaybackSnapshot? =
            snapshot?.takeIf { it.roomId == roomId }

        override suspend fun publishPlayback(command: RoomCommand): RoomPublishResult {
            val gate = publishGate
            publishGate = null
            gate?.await()
            actionRevisions[command.actionId]?.let { existingRevision ->
                return RoomPublishResult(applied = true, revision = existingRevision)
            }
            published += command
            val revision = command.expectedRevision + 1L
            actionRevisions[command.actionId] = revision
            val timestamp = "1970-01-01T00:00:%02d.%03dZ".format(
                serverNowMs / 1_000L,
                serverNowMs % 1_000L
            )
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
                updatedAt = timestamp,
                actionId = command.actionId,
                actionType = command.actionType,
                originDeviceId = command.originDeviceId,
                commandAt = timestamp
            )
            return RoomPublishResult(applied = true, revision = revision)
        }

        override suspend fun markPlaybackReady(
            roomId: String,
            revision: Long,
            sourceId: String
        ): RoomPlaybackReadyResult {
            readinessCalls += 1
            readinessSourceIds += sourceId
            val current = checkNotNull(snapshot) { "readiness requires an authoritative playback snapshot" }
            check(current.roomId == roomId && current.revision == revision) {
                "readiness revision must match the authoritative snapshot"
            }
            check(current.sourceId == sourceId &&
                current.actionType == RoomActionType.TRACK &&
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

        fun emit(signal: RoomRealtimeSignal) {
            signals.tryEmit(signal)
        }
    }

    private class FakeRoomPlayer(
        initial: LocalPlaybackSnapshot = LocalPlaybackSnapshot()
    ) : RoomPlayerPort {
        private val mutablePlayback = MutableStateFlow(initial)
        private val mutableIntents = MutableSharedFlow<RoomPlayerIntent>(extraBufferCapacity = 16)
        var seekCalls = 0
            private set
        var speedChanges = 0
            private set

        override val userIntents: SharedFlow<RoomPlayerIntent> = mutableIntents

        override fun snapshot(): LocalPlaybackSnapshot = mutablePlayback.value

        override suspend fun prepareCanonicalSource(
            canonicalSourceUrl: String,
            streamUrl: String,
            title: String,
            artist: String?,
            mimeType: String?
        ) {
            mutablePlayback.value = mutablePlayback.value.copy(
                sourceId = canonicalSourceUrl,
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
            seekCalls += 1
            mutablePlayback.value = mutablePlayback.value.copy(
                positionMs = positionMs.coerceAtLeast(0L)
            )
        }

        override fun setPlaybackSpeed(speed: Float) {
            speedChanges += 1
        }

        override fun resetPlaybackSpeed() = Unit

        fun emitIntent(intent: RoomPlayerIntent) {
            mutableIntents.tryEmit(intent)
        }

        fun setSnapshot(snapshot: LocalPlaybackSnapshot) {
            mutablePlayback.value = snapshot
        }
    }

    private class FakeRoomClock(
        private var failuresBeforeSuccess: Int = 0
    ) : RoomClock {
        private var elapsedMs = 10_000L
        private var calibrated = failuresBeforeSuccess == 0
        var calibrationCalls = 0
            private set
        override fun elapsedRealtimeMs(): Long = elapsedMs
        override fun estimatedServerTimeMs(): Long = elapsedMs
        override fun estimatedRoundTripTimeMs(): Long = 25L
        override fun isCalibrated(): Boolean = calibrated
        override suspend fun calibrate() {
            calibrationCalls += 1
            elapsedMs += 1L
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess -= 1
                throw IOException("Temporary clock probe failure")
            }
            calibrated = true
        }

        fun advance(milliseconds: Long) {
            elapsedMs += milliseconds.coerceAtLeast(0L)
        }
    }

    private class FakeRoomMediaResolver(
        private val failuresBeforeSuccess: Int = 0
    ) : RoomMediaResolver {
        var resolveCount = 0

        override suspend fun resolve(canonicalSourceUrl: String): ResolvedAudio {
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
