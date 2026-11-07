package com.nexus.music.rooms

import com.nexus.music.rooms.domain.RoomActionType
import com.nexus.music.rooms.domain.RoomClock
import com.nexus.music.rooms.domain.RoomCommand
import com.nexus.music.rooms.domain.RoomConnectionState
import com.nexus.music.rooms.domain.RoomController
import com.nexus.music.rooms.domain.RoomControllerState
import com.nexus.music.rooms.domain.RoomDiagnosticsSnapshot
import com.nexus.music.rooms.domain.RoomPlayerIntent
import com.nexus.music.rooms.domain.RoomPlayerPort
import com.nexus.music.rooms.domain.RoomPlaybackSnapshot
import com.nexus.music.rooms.domain.RoomPlaybackReadyResult
import com.nexus.music.rooms.domain.RoomRealtimeSignal
import com.nexus.music.rooms.domain.RoomRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive

/**
 * Service-lifetime room coordinator.
 *
 * It owns the active room's heartbeat, realtime subscription, authoritative snapshot
 * reconciliation, local-intent publication, clock recalibration and drift correction.
 * UI lifecycle is deliberately not part of this class.
 */
internal class ServiceRoomController(
    parentScope: CoroutineScope,
    private val repository: RoomRepository,
    private val player: RoomPlayerPort,
    private val clock: RoomClock,
    private val mediaResolver: RoomMediaResolver
) : RoomController {

    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val controllerScope = CoroutineScope(
        parentScope.coroutineContext + controllerJob
    )
    private val mutableState = MutableStateFlow(RoomControllerState())
    override val state: StateFlow<RoomControllerState> = mutableState.asStateFlow()
    private val mutableDiagnostics = MutableStateFlow(RoomDiagnosticsSnapshot())
    override val diagnostics: StateFlow<RoomDiagnosticsSnapshot> = mutableDiagnostics.asStateFlow()

    private val publishMutex = Mutex()
    private val applyMutex = Mutex()
    private val applyingRevisions = mutableSetOf<Long>()
    private val driftController = DriftCorrectionController()
    private val driftSeekGate = DriftSeekGate()
    private val retryPolicy = RemoteApplyRetryPolicy()

    internal enum class PlaybackPhase(
        val isRoomActive: Boolean,
        val shouldPlay: Boolean,
        val holdsPlay: Boolean,
        val isSystemPaused: Boolean
    ) {
        LOCAL(false, false, false, false),
        ROOM_PAUSED(true, false, false, false),
        ROOM_PLAYING(true, true, false, false),
        TRACK_PENDING_PAUSED(true, false, false, false),
        TRACK_PENDING_PLAYING(true, true, true, false),
        START_PREPARING(true, true, true, false),
        START_SCHEDULED(true, true, true, false),
        SYSTEM_PAUSED_PLAYING(true, true, false, true),
        SYSTEM_PAUSED_PAUSED(true, false, false, true),
        SYSTEM_PAUSED_TRACK_PENDING_PLAYING(true, true, true, true),
        SYSTEM_PAUSED_TRACK_PENDING_PAUSED(true, false, false, true)
    }

    @Volatile private var playbackPhase = PlaybackPhase.LOCAL

    private var roomJob: Job? = null
    private var roomScope: CoroutineScope? = null
    private var reconcileJob: Job? = null
    private var reconcileRequested = false
    private var pendingRealtimeSnapshot: RoomPlaybackSnapshot? = null
    private var publishJob: Job? = null
    private var localTrackFallbackJob: Job? = null
    private var startJob: Job? = null
    private var startRevision: Long? = null
    private var scheduledStartAt: String? = null
    private var completedStartRevision: Long = 0L
    private var playbackAnchorRevision: Long? = null
    private var playbackAnchorAt: String? = null
    private var playbackAnchorPositionMs: Long? = null

    @Volatile private var activeRoomId: String? = null
    private var sessionGeneration = 0L
    private var localActionGeneration = 0L
    private var userId: String? = null
    private var lastRemoteRevision = 0L
    private var lastAppliedRevision = 0L
    private var lastRemotePlayback: RoomPlaybackSnapshot? = null
    private var pendingAction: PendingLocalAction? = null
    private var signalSequence = 0L

    private data class PendingLocalAction(
        val actionId: String,
        val generation: Long,
        val elapsedMs: Long,
        val actionType: RoomActionType,
        val sourceId: String,
        val canonicalSourceUrl: String,
        val title: String,
        val artist: String?,
        val shouldPlay: Boolean = false,
        val requestedPositionMs: Long? = null
    )

    init {
        controllerScope.launch {
            player.userIntents.collect { intent ->
                captureLocalIntent(intent)
            }
        }
    }

    override suspend fun attach(roomId: String) {
        require(roomId.isNotBlank()) { "Room ID must not be blank." }
        stopRoomSession()
        val generation = ++sessionGeneration
        localActionGeneration = 0L
        activeRoomId = roomId
        playbackPhase = PlaybackPhase.ROOM_PAUSED
        mutableDiagnostics.value = RoomDiagnosticsSnapshot()
        recordDiagnostic("room.attach", "room=$roomId")
        mutableState.value = RoomControllerState(
            roomId = roomId,
            connection = RoomConnectionState.CONNECTING
        )
        setPendingAction(null)
        lastRemoteRevision = 0L
        lastAppliedRevision = 0L
        lastRemotePlayback = null
        refreshPlaybackPhase()
        retryPolicy.reset()
        driftController.reset()
        driftSeekGate.reset()
        signalSequence = 0L
        startRevision = null
        completedStartRevision = 0L
        playbackAnchorRevision = null
        playbackAnchorAt = null
        playbackAnchorPositionMs = null

        val newUserId = repository.currentUserId()
        if (!isCurrent(roomId, generation)) return
        userId = newUserId

        // Keep a previous valid estimate when a network probe temporarily fails, but
        // never treat the wall-clock fallback as a valid synchronized server clock.
        try {
            clock.calibrate()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordDiagnostic("clock.calibration_failed", error.message.orEmpty())
        }
        if (!isCurrent(roomId, generation)) return

        val initial = repository.fetchPlaybackSnapshot(roomId)
        if (!isCurrent(roomId, generation)) return
        if (initial != null) {
            setRemotePlayback(initial)
            lastRemoteRevision = initial.revision
            lastAppliedRevision = 0L
            mutableState.value = mutableState.value.copy(playback = initial)
        }

        val parent = SupervisorJob(controllerJob)
        val childScope = CoroutineScope(controllerScope.coroutineContext + parent)
        roomJob = parent
        roomScope = childScope

        childScope.launch {
            repository.observeRealtimeSignals(roomId).collect { signal ->
                if (!isCurrent(roomId, generation)) return@collect
                when (signal) {
                    RoomRealtimeSignal.PlaybackInvalidated -> {
                        signalSequence++
                        recordDiagnostic("realtime.playback_signal", "sequence=$signalSequence")
                        requestReconciliation(roomId, generation)
                    }
                    is RoomRealtimeSignal.PlaybackUpdated -> {
                        signalSequence++
                        recordDiagnostic(
                            "realtime.playback_snapshot",
                            "revision=${signal.snapshot.revision} sequence=$signalSequence"
                        )
                        requestReconciliation(roomId, generation, signal.snapshot)
                    }
                    RoomRealtimeSignal.ChatInvalidated -> {
                        mutableState.value = mutableState.value.copy(
                            chatSignalVersion = mutableState.value.chatSignalVersion + 1L
                        )
                    }
                    is RoomRealtimeSignal.StartScheduled -> {
                        onBarrierStartScheduled(
                            roomId = roomId,
                            generation = generation,
                            revision = signal.revision,
                            sourceId = signal.sourceId,
                            startAt = signal.startAt
                        )
                    }
                    is RoomRealtimeSignal.ConnectionChanged -> {
                        mutableState.value = mutableState.value.copy(
                            realtimeConnected = signal.connected,
                            connection = if (signal.connected) {
                                RoomConnectionState.CONNECTED
                            } else {
                                RoomConnectionState.CONNECTING
                            }
                        )
                        if (signal.connected) requestReconciliation(roomId, generation)
                    }
                }
            }
        }

        childScope.launch {
            driftLoop(roomId, generation)
        }
        childScope.launch {
            pollingLoop(roomId, generation)
        }

        mutableState.value = mutableState.value.copy(
            connection = RoomConnectionState.CONNECTING,
            lastError = null
        )
        if (initial != null) {
            requestReconciliation(roomId, generation)
        } else {
            val local = player.snapshot()
            if (local.sourceId.isNotBlank() && local.title.isNotBlank()) {
                captureLocalIntent(
                    RoomPlayerIntent.SelectTrack(
                        sourceId = local.sourceId,
                        canonicalSourceUrl = local.canonicalSourceUrl.ifBlank { local.sourceId },
                        title = local.title,
                        artist = local.artist,
                        shouldPlay = local.playWhenReady
                    )
                )
            } else {
                mutableState.value = mutableState.value.copy(
                    connection = RoomConnectionState.CONNECTED
                )
            }
        }
    }

    override suspend fun detach() {
        stopRoomSession()
        activeRoomId = null
        playbackPhase = PlaybackPhase.LOCAL
        userId = null
        setPendingAction(null)
        mutableState.value = RoomControllerState()
        lastRemotePlayback = null
        lastRemoteRevision = 0L
        lastAppliedRevision = 0L
        localActionGeneration = 0L
        retryPolicy.reset()
        driftController.reset()
        driftSeekGate.reset()
        player.resetPlaybackSpeed()
    }

    fun close() {
        stopRoomSession()
        activeRoomId = null
        playbackPhase = PlaybackPhase.LOCAL
        player.resetPlaybackSpeed()
        controllerJob.cancel()
        mutableState.value = RoomControllerState()
    }

    private fun stopRoomSession() {
        sessionGeneration++
        roomJob?.cancel()
        roomJob = null
        roomScope?.cancel()
        roomScope = null
        reconcileJob?.cancel()
        reconcileJob = null
        reconcileRequested = false
        pendingRealtimeSnapshot = null
        publishJob?.cancel()
        publishJob = null
        localTrackFallbackJob?.cancel()
        localTrackFallbackJob = null
        clearStart()
    }

    private fun clearStart(cancelJob: Boolean = true) {
        if (cancelJob) startJob?.cancel()
        startJob = null
        startRevision = null
        scheduledStartAt = null
    }

    private fun isCurrent(roomId: String, generation: Long): Boolean =
        activeRoomId == roomId && sessionGeneration == generation

    private fun recordDiagnostic(
        event: String,
        detail: String = "",
        publishLatencyMs: Long? = null,
        snapshotLatencyMs: Long? = null,
        applyLatencyMs: Long? = null,
        driftMs: Long? = null,
        commandAgeMs: Long? = null
    ) {
        val elapsed = clock.elapsedRealtimeMs()
        val wall = System.currentTimeMillis()
        val serverNow = runCatching { clock.estimatedServerTimeMs() }.getOrNull()
        val measuredRtt = clock.estimatedRoundTripTimeMs().takeIf { it >= 0L }
        val line = "wall=$wall mono=$elapsed server=${serverNow ?: "unknown"} $event" +
            detail.takeIf(String::isNotBlank)?.let { " | $it" }.orEmpty()
        val previous = mutableDiagnostics.value
        mutableDiagnostics.value = previous.copy(
            lastPublishLatencyMs = publishLatencyMs ?: previous.lastPublishLatencyMs,
            lastSnapshotLatencyMs = snapshotLatencyMs ?: previous.lastSnapshotLatencyMs,
            lastApplyLatencyMs = applyLatencyMs ?: previous.lastApplyLatencyMs,
            lastDriftMs = driftMs ?: previous.lastDriftMs,
            lastRoundTripMs = measuredRtt ?: previous.lastRoundTripMs,
            lastCommandAgeMs = commandAgeMs ?: previous.lastCommandAgeMs,
            events = (previous.events + line).takeLast(MAX_DIAGNOSTIC_EVENTS)
        )
    }

    /** The sole playback-state contract consumed by the MediaSession player wrapper. */
    fun playbackPhase(intent: RoomPlayerIntent? = null): PlaybackPhase {
        if (intent == null) return playbackPhase
        val phase = playbackPhase
        val roomActive = phase.isRoomActive
        val interrupted = phase.isSystemPaused
        val pendingTrack = phase == PlaybackPhase.TRACK_PENDING_PLAYING ||
            phase == PlaybackPhase.TRACK_PENDING_PAUSED
        playbackPhase = when (intent) {
            RoomPlayerIntent.SystemPause -> if (phase.shouldPlay) PlaybackPhase.SYSTEM_PAUSED_PLAYING
                else PlaybackPhase.SYSTEM_PAUSED_PAUSED
            RoomPlayerIntent.SystemResume -> phaseFromRoomState()
            is RoomPlayerIntent.SelectTrack -> when {
                !roomActive -> PlaybackPhase.LOCAL
                interrupted && intent.shouldPlay -> PlaybackPhase.SYSTEM_PAUSED_TRACK_PENDING_PLAYING
                interrupted -> PlaybackPhase.SYSTEM_PAUSED_TRACK_PENDING_PAUSED
                intent.shouldPlay -> PlaybackPhase.TRACK_PENDING_PLAYING
                else -> PlaybackPhase.TRACK_PENDING_PAUSED
            }
            RoomPlayerIntent.Play -> when {
                interrupted || phase.holdsPlay -> phase
                roomActive -> PlaybackPhase.ROOM_PLAYING
                else -> PlaybackPhase.LOCAL
            }
            RoomPlayerIntent.Pause -> when {
                interrupted -> phase
                !roomActive -> PlaybackPhase.LOCAL
                pendingTrack -> PlaybackPhase.TRACK_PENDING_PAUSED
                else -> PlaybackPhase.ROOM_PAUSED
            }
            is RoomPlayerIntent.SeekTo -> when {
                interrupted || pendingTrack -> phase
                !roomActive -> PlaybackPhase.LOCAL
                phase.shouldPlay -> PlaybackPhase.ROOM_PLAYING
                else -> PlaybackPhase.ROOM_PAUSED
            }
        }
        return playbackPhase
    }

    private fun phaseFromRoomState(): PlaybackPhase = when {
        activeRoomId == null -> PlaybackPhase.LOCAL
        pendingAction?.actionType == RoomActionType.TRACK ->
            if (pendingAction?.shouldPlay == true) PlaybackPhase.TRACK_PENDING_PLAYING
            else PlaybackPhase.TRACK_PENDING_PAUSED
        pendingAction != null ->
            if (pendingAction?.shouldPlay == true) PlaybackPhase.ROOM_PLAYING
            else PlaybackPhase.ROOM_PAUSED
        startRevision != null && scheduledStartAt == null -> PlaybackPhase.START_PREPARING
        startRevision != null -> PlaybackPhase.START_SCHEDULED
        lastRemotePlayback?.isPlaying == true -> PlaybackPhase.ROOM_PLAYING
        else -> PlaybackPhase.ROOM_PAUSED
    }

    private fun refreshPlaybackPhase() {
        if (!playbackPhase.isSystemPaused) playbackPhase = phaseFromRoomState()
    }

    private fun setPendingAction(action: PendingLocalAction?) {
        pendingAction = action
        refreshPlaybackPhase()
    }

    private fun setRemotePlayback(snapshot: RoomPlaybackSnapshot) {
        lastRemotePlayback = snapshot
        refreshPlaybackPhase()
    }

    private fun captureLocalIntent(intent: RoomPlayerIntent) {
        val roomId = activeRoomId ?: return

        if (intent != RoomPlayerIntent.SystemPause && intent != RoomPlayerIntent.SystemResume) {
            playbackPhase(intent)
        }
        if (intent == RoomPlayerIntent.SystemPause) {
            playbackPhase = if (playbackPhase.shouldPlay || player.snapshot().playWhenReady) {
                PlaybackPhase.SYSTEM_PAUSED_PLAYING
            } else PlaybackPhase.SYSTEM_PAUSED_PAUSED
            clearStart()
            mutableState.value = mutableState.value.copy(statusMessage = null)
            player.resetPlaybackSpeed()
            driftController.reset()
            driftSeekGate.reset()
            recordDiagnostic("local.system_pause", "local-only; room timeline unchanged; pending start suspended")
            return
        }
        if (playbackPhase.isSystemPaused) {
            val remote = lastRemotePlayback
            playbackPhase = phaseFromRoomState()
            when (intent) {
                RoomPlayerIntent.SystemResume -> {
                    resumeAfterInterruption(roomId, remote, "system_resume", requirePlayWhenReady = true)
                    return
                }
                RoomPlayerIntent.Play -> if (remote?.isPlaying == true) {
                    resumeAfterInterruption(roomId, remote, "explicit_play", requirePlayWhenReady = false)
                    return
                } else recordDiagnostic(
                    "local.system_pause_released",
                    "room is not playing; explicit Play may publish"
                )
                else -> recordDiagnostic("local.system_pause_overridden", "explicit user intent=$intent")
            }
        }

        val previousAction = pendingAction
        val isPendingTrackPlay = intent is RoomPlayerIntent.Play &&
            previousAction?.actionType == RoomActionType.TRACK
        if (intent is RoomPlayerIntent.Play && !isPendingTrackPlay && playbackPhase.holdsPlay) {
            // The room already has a new-track start in progress. Keep the player staged;
            // a local Play button must not cancel the peer barrier or publish a plain PLAY.
            player.pause()
            recordDiagnostic("local.play_held_for_barrier", "revision=$startRevision")
            return
        }
        if (intent is RoomPlayerIntent.SelectTrack && intent.shouldPlay) {
            // Stop the previous item before the selected item can inherit playWhenReady.
            // Keep the requested state in the pending TRACK command for the shared barrier.
            player.pause()
        }
        if (!isPendingTrackPlay) cancelStartBarrier("new local playback intent")
        localTrackFallbackJob?.cancel()
        localTrackFallbackJob = null
        playbackAnchorRevision = null
        playbackAnchorAt = null
        playbackAnchorPositionMs = null
        val elapsed = clock.elapsedRealtimeMs()
        val current = player.snapshot()
        val previous = pendingAction
        val next = when (intent) {
            is RoomPlayerIntent.SelectTrack -> PendingLocalAction(
                actionId = UUID.randomUUID().toString(),
                generation = ++localActionGeneration,
                elapsedMs = elapsed,
                actionType = RoomActionType.TRACK,
                sourceId = intent.sourceId,
                canonicalSourceUrl = intent.canonicalSourceUrl,
                title = intent.title,
                artist = intent.artist,
                shouldPlay = intent.shouldPlay
            )
            RoomPlayerIntent.Play -> previous?.takeIf { it.actionType == RoomActionType.TRACK }
                ?.let { updatePendingTrack(it, elapsed, shouldPlay = true) }
                ?: pendingFromSnapshot(RoomActionType.PLAY, current, elapsed)
            RoomPlayerIntent.Pause -> previous?.takeIf { it.actionType == RoomActionType.TRACK }
                ?.let { updatePendingTrack(it, elapsed, shouldPlay = false) }
                ?: pendingFromSnapshot(RoomActionType.PAUSE, current, elapsed)
            is RoomPlayerIntent.SeekTo -> previous?.takeIf { it.actionType == RoomActionType.TRACK }
                ?.let { updatePendingTrack(it, elapsed, requestedPositionMs = intent.positionMs) }
                ?: pendingFromSnapshot(RoomActionType.SEEK, current, elapsed, intent.positionMs)
            RoomPlayerIntent.SystemPause,
            RoomPlayerIntent.SystemResume -> null
        } ?: return

        setPendingAction(next)
        recordDiagnostic("local.intent", "type=${next.actionType} source=${next.sourceId} generation=${next.generation}")
        val scope = roomScope ?: return
        if (next.actionType == RoomActionType.TRACK && next.shouldPlay) {
            // A room/network failure must not keep a locally prepared track silent.
            // After three seconds, start this device and publish a RECOVERY timeline.
            val expectedSession = sessionGeneration
            localTrackFallbackJob = scope.launch {
                delay(LOCAL_TRACK_FALLBACK_TIMEOUT_MS)
                if (!isCurrent(roomId, expectedSession) ||
                    pendingAction?.generation != next.generation ||
                    playbackPhase.isSystemPaused
                ) return@launch
                val local = player.snapshot()
                if (local.sourceId != next.canonicalSourceUrl || !local.isReady) return@launch

                player.seekTo(next.requestedPositionMs ?: 0L)
                player.resetPlaybackSpeed()
                player.play()
                val fallback = next.copy(
                    actionId = UUID.randomUUID().toString(),
                    generation = ++localActionGeneration,
                    elapsedMs = clock.elapsedRealtimeMs(),
                    actionType = RoomActionType.RECOVERY,
                    shouldPlay = true
                )
                setPendingAction(fallback)
                recordDiagnostic("local.track_fallback", "source=${fallback.sourceId} reason=room_start_timeout")
                schedulePendingPublish(roomId, expectedSession)
            }
        }
        schedulePendingPublish(roomId, sessionGeneration)
    }

    private fun schedulePendingPublish(roomId: String, generation: Long) {
        val scope = roomScope ?: return
        if (publishJob?.isActive == true) return
        publishJob = scope.launch {
            val publishingJob = coroutineContext[Job]
            var retryGeneration = Long.MIN_VALUE
            var retryCount = 0
            try {
                if (pendingAction?.let {
                        it.actionType != RoomActionType.RECOVERY &&
                            (it.actionType != RoomActionType.TRACK || !it.shouldPlay)
                    } == true) delay(100L)
                while (isCurrent(roomId, generation)) {
                    val pending = pendingAction ?: break
                    if (pending.generation != retryGeneration) { retryGeneration = pending.generation; retryCount = 0 }
                    try {
                        publishPendingAction(roomId, generation)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (pendingAction?.generation != pending.generation) continue
                        retryCount = (retryCount + 1).coerceAtMost(5)
                        val retryIn = publishRetryDelay(retryCount)
                        setError(error, "Playback command could not be published")
                        recordDiagnostic(
                            "rpc.publish_retry_scheduled",
                            "generation=${pending.generation} attempt=$retryCount delay=${retryIn}ms"
                        )
                        delay(retryIn)
                        continue
                    }
                    if (pendingAction?.generation == pending.generation) {
                        retryCount = (retryCount + 1).coerceAtMost(5)
                        val retryIn = publishRetryDelay(retryCount)
                        delay(retryIn)
                    }
                }
            } finally {
                if (publishJob === publishingJob) {
                    publishJob = null
                    if (isCurrent(roomId, generation) && pendingAction != null) {
                        schedulePendingPublish(roomId, generation)
                    }
                }
            }
        }
    }

    private fun publishRetryDelay(attempt: Int): Long =
        (PUBLISH_RETRY_BASE_MS * (1L shl (attempt - 1))).coerceAtMost(PUBLISH_RETRY_MAX_MS)

    private fun updatePendingTrack(
        previous: PendingLocalAction,
        elapsed: Long,
        shouldPlay: Boolean = previous.shouldPlay,
        requestedPositionMs: Long? = previous.requestedPositionMs
    ) = previous.copy(
        actionId = UUID.randomUUID().toString(),
        generation = ++localActionGeneration,
        elapsedMs = elapsed,
        shouldPlay = shouldPlay,
        requestedPositionMs = requestedPositionMs
    )

    private fun pendingFromSnapshot(
        type: RoomActionType,
        snapshot: com.nexus.music.rooms.domain.LocalPlaybackSnapshot,
        elapsed: Long,
        requestedPositionMs: Long? = null
    ): PendingLocalAction? {
        val source = snapshot.sourceId.takeIf(String::isNotBlank) ?: return null
        val canonical = snapshot.canonicalSourceUrl.ifBlank { source }
        val title = snapshot.title.takeIf(String::isNotBlank) ?: return null
        return PendingLocalAction(
            actionId = UUID.randomUUID().toString(),
            generation = ++localActionGeneration,
            elapsedMs = elapsed,
            actionType = type,
            sourceId = source,
            canonicalSourceUrl = canonical,
            title = title,
            artist = snapshot.artist,
            shouldPlay = when (type) {
                RoomActionType.PLAY -> true
                RoomActionType.PAUSE -> false
                else -> snapshot.playWhenReady
            },
            requestedPositionMs = requestedPositionMs?.coerceAtLeast(0L)
        )
    }

    private suspend fun pollingLoop(roomId: String, generation: Long) {
        var lastHeartbeat = clock.elapsedRealtimeMs() - HEARTBEAT_INTERVAL_MS
        var lastCalibration = clock.elapsedRealtimeMs()
        var consecutiveFailures = 0
        while (isCurrent(roomId, generation) && coroutineContext.isActive) {
            var failed = false
            val now = clock.elapsedRealtimeMs()

            if (now - lastCalibration >= CLOCK_CALIBRATION_INTERVAL_MS) {
                try {
                    clock.calibrate()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Retain the previous calibrated estimate; never replace it with the
                    // device wall clock just because a refresh probe temporarily failed.
                    recordDiagnostic("clock.recalibration_failed", error.message.orEmpty())
                }
                lastCalibration = clock.elapsedRealtimeMs()
            }

            if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                try {
                    val active = repository.touchRoom(roomId)
                    if (!isCurrent(roomId, generation)) return
                    if (!active) {
                        mutableState.value = mutableState.value.copy(
                            connection = RoomConnectionState.ERROR,
                            lastError = "This room has expired or your membership ended."
                        )
                        detach()
                        return
                    }
                    lastHeartbeat = clock.elapsedRealtimeMs()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failed = true
                    setError(error, "Room heartbeat failed")
                }
            }

            try {
                refreshRemotePlayback(roomId, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed = true
                setError(error, "Playback reconciliation failed")
            }

            consecutiveFailures = if (failed) {
                (consecutiveFailures + 1).coerceAtMost(4)
            } else {
                0
            }
            val base = if (mutableState.value.realtimeConnected) 8_000L else 2_000L
            val nextDelay = if (consecutiveFailures == 0) {
                base
            } else {
                (base * (1L shl consecutiveFailures)).coerceAtMost(20_000L)
            }
            delay(nextDelay)
        }
    }

    private suspend fun driftLoop(roomId: String, generation: Long) {
        while (isCurrent(roomId, generation) && coroutineContext.isActive) {
            lastRemotePlayback?.let(::correctLocalDrift)
            delay(1_000L)
        }
    }

    private fun requestReconciliation(
        roomId: String,
        generation: Long,
        realtimeSnapshot: RoomPlaybackSnapshot? = null
    ) {
        if (!isCurrent(roomId, generation)) return
        if (realtimeSnapshot != null) {
            val pending = pendingRealtimeSnapshot
            if (pending == null || realtimeSnapshot.revision >= pending.revision) {
                pendingRealtimeSnapshot = realtimeSnapshot
            }
        }
        reconcileRequested = true
        if (reconcileJob?.isActive == true) return
        val scope = roomScope ?: return
        reconcileJob = scope.launch {
            do {
                reconcileRequested = false
                val snapshot = pendingRealtimeSnapshot
                pendingRealtimeSnapshot = null
                try {
                    // Keep expensive stream resolution and Media3 application out of the
                    // realtime collector. The collector can continue receiving newer rows.
                    refreshRemotePlayback(roomId, generation, snapshot)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    setError(error, "Playback reconnecting")
                }
            } while (
                (reconcileRequested || pendingRealtimeSnapshot != null) &&
                isCurrent(roomId, generation) &&
                coroutineContext.isActive
            )
        }
    }

    private suspend fun publishPendingAction(roomId: String, generation: Long) {
        publishMutex.withLock {
            if (!isCurrent(roomId, generation)) return
            val pending = pendingAction ?: return
            val deviceId = userId ?: return
            val local = player.snapshot()
            // Never let a delayed MediaSession callback publish the previous item under a
            // newly selected TRACK action. Wait for ExoPlayer to expose the selected media ID.
            if (pending.actionType == RoomActionType.TRACK &&
                local.sourceId.isNotBlank() &&
                local.sourceId != pending.sourceId
            ) return
            val sourceId = if (pending.actionType == RoomActionType.TRACK) {
                pending.sourceId
            } else {
                local.sourceId.ifBlank { pending.sourceId }
            }
            val canonicalUrl = pending.canonicalSourceUrl.ifBlank {
                local.canonicalSourceUrl.ifBlank { sourceId }
            }
            val title = if (pending.actionType == RoomActionType.TRACK) {
                pending.title
            } else {
                local.title.ifBlank { pending.title }
            }
            if (sourceId.isBlank() || canonicalUrl.isBlank() || title.isBlank()) return

            val command = RoomCommand(
                roomId = roomId,
                actionId = pending.actionId,
                expectedRevision = lastRemoteRevision,
                originDeviceId = deviceId,
                actionType = pending.actionType,
                sourceId = sourceId,
                canonicalSourceUrl = canonicalUrl,
                title = title,
                artist = local.artist ?: pending.artist,
                // New tracks start at zero unless an explicit seek was captured before publish.
                // The requested play state is retained while the local player stays paused.
                isPlaying = when (pending.actionType) {
                    RoomActionType.TRACK, RoomActionType.PLAY, RoomActionType.RECOVERY -> pending.shouldPlay
                    RoomActionType.PAUSE -> false
                    else -> local.playWhenReady
                },
                positionMs = (pending.requestedPositionMs ?: if (pending.actionType == RoomActionType.TRACK) {
                    0L
                } else {
                    local.positionMs
                }).coerceAtLeast(0L),
                durationMs = local.durationMs.coerceAtLeast(0L)
            )

            val publishStarted = clock.elapsedRealtimeMs()
            val result = repository.publishPlayback(command)
            val publishLatency = (clock.elapsedRealtimeMs() - publishStarted).coerceAtLeast(0L)
            recordDiagnostic(
                "rpc.publish",
                "type=${pending.actionType} applied=${result.applied} revision=${result.revision} " +
                    "intent_to_request=${(publishStarted - pending.elapsedMs).coerceAtLeast(0L)}ms",
                publishLatencyMs = publishLatency
            )
            if (!isCurrent(roomId, generation)) return
            if (result.applied) {
                acceptPublished(command, result.revision, pending)
                return
            }

            val latest = repository.fetchPlaybackSnapshot(roomId) ?: return
            if (!isCurrent(roomId, generation)) return
            setRemotePlayback(latest)
            lastRemoteRevision = maxOf(lastRemoteRevision, latest.revision)
            val currentPending = pendingAction
            val newerPending = currentPending != null &&
                currentPending.generation != pending.generation
            val candidate = if (newerPending) checkNotNull(currentPending) else pending
            val localActionServerMs = clock.estimatedServerTimeMs() -
                (clock.elapsedRealtimeMs() - candidate.elapsedMs).coerceAtLeast(0L)
            val remoteActionServerMs = parseTimestamp(
                latest.commandAt.ifBlank { latest.updatedAt }
            ) ?: Long.MAX_VALUE
            val localWins = localActionServerMs >
                remoteActionServerMs + LOCAL_ACTION_WIN_MARGIN_MS

            if (newerPending) {
                // Never retry the stale command captured before a newer local action.
                // Let the newest intent compete against the refreshed revision instead.
                if (!localWins) setPendingAction(null)
                requestReconciliation(roomId, generation)
                if (localWins) requestPublishAfterApplyLock(roomId, generation)
                return
            }

            if (localWins) {
                val rebased = command.copy(expectedRevision = latest.revision)
                val retryStarted = clock.elapsedRealtimeMs()
                val retry = repository.publishPlayback(rebased)
                recordDiagnostic(
                    "rpc.publish_retry",
                    "applied=${retry.applied} revision=${retry.revision}",
                    publishLatencyMs = (clock.elapsedRealtimeMs() - retryStarted).coerceAtLeast(0L)
                )
                if (!isCurrent(roomId, generation)) return
                if (retry.applied) {
                    acceptPublished(rebased, retry.revision, pending)
                    return
                }
            }

            val authoritative = repository.fetchPlaybackSnapshot(roomId) ?: latest
            if (!isCurrent(roomId, generation)) return
            setRemotePlayback(authoritative)
            lastRemoteRevision = maxOf(lastRemoteRevision, authoritative.revision)
            if (!localWins) {
                setPendingAction(null)
                requestReconciliation(roomId, generation)
            }
        }
    }

    private fun acceptPublished(
        command: RoomCommand,
        revision: Long,
        pending: PendingLocalAction
    ) {
        lastRemoteRevision = maxOf(lastRemoteRevision, revision)
        lastAppliedRevision = maxOf(lastAppliedRevision, revision)
        retryPolicy.recordSuccess(revision)
        val newerPending = pendingAction?.generation?.let { it != pending.generation } == true
        if (pendingAction?.generation == pending.generation) {
            setPendingAction(null)
            localTrackFallbackJob?.cancel()
            localTrackFallbackJob = null
        }
        val publishedSnapshot = RoomPlaybackSnapshot(
            roomId = command.roomId,
            sourceId = command.sourceId,
            canonicalSourceUrl = command.canonicalSourceUrl,
            title = command.title,
            artist = command.artist,
            isPlaying = command.isPlaying,
            positionMs = command.positionMs,
            durationMs = command.durationMs,
            revision = revision,
            updatedBy = userId.orEmpty(),
            updatedAt = serverTimestamp(),
            actionId = command.actionId,
            actionType = command.actionType,
            originDeviceId = command.originDeviceId,
            commandAt = serverTimestamp()
        )
        setRemotePlayback(publishedSnapshot)
        if (command.actionType == RoomActionType.RECOVERY && command.isPlaying) {
            completedStartRevision = maxOf(completedStartRevision, revision)
            playbackAnchorRevision = revision
            playbackAnchorAt = publishedSnapshot.commandAt
            playbackAnchorPositionMs = command.positionMs
            recordDiagnostic("barrier.recovery_published", "revision=$revision anchor=${publishedSnapshot.commandAt}")
        } else if (command.actionType != RoomActionType.TRACK) {
            playbackAnchorRevision = null
            playbackAnchorAt = null
            playbackAnchorPositionMs = null
        }
        mutableState.value = mutableState.value.copy(
            connection = RoomConnectionState.CONNECTED,
            playback = publishedSnapshot,
            lastError = null,
            statusMessage = null
        )
        if (command.isPlaying && command.actionType == RoomActionType.TRACK && !newerPending) {
            beginPlaybackStartBarrier(
                roomId = command.roomId,
                generation = sessionGeneration,
                revision = revision,
                sourceId = command.sourceId
            )
        } else if (newerPending) {
            requestPublishAfterApplyLock(command.roomId, sessionGeneration)
        }
    }

    private suspend fun refreshRemotePlayback(
        roomId: String,
        generation: Long,
        realtimeSnapshot: RoomPlaybackSnapshot? = null
    ) {
        if (!isCurrent(roomId, generation)) return
        // Realtime carries the authoritative row on the private room topic. REST remains
        // the reconnect and legacy-payload fallback, not the cost of every playback change.
        val remote: RoomPlaybackSnapshot
        if (realtimeSnapshot == null) {
            val snapshotStarted = clock.elapsedRealtimeMs()
            remote = repository.fetchPlaybackSnapshot(roomId) ?: return
            val snapshotLatency = (clock.elapsedRealtimeMs() - snapshotStarted).coerceAtLeast(0L)
            recordDiagnostic(
                "rest.snapshot",
                "revision=${remote.revision}",
                snapshotLatencyMs = snapshotLatency
            )
        } else {
            remote = realtimeSnapshot
            recordDiagnostic("realtime.snapshot_apply", "revision=${remote.revision}")
        }
        if (!isCurrent(roomId, generation) || remote.roomId != roomId) return

        var shouldApply = false
        var shouldPublishLocal = false
        applyMutex.withLock {
            if (!isCurrent(roomId, generation) || remote.revision < lastRemoteRevision) {
                return@withLock
            }
            setRemotePlayback(remote)
            lastRemoteRevision = maxOf(lastRemoteRevision, remote.revision)
            mutableState.value = mutableState.value.copy(
                connection = if (mutableState.value.realtimeConnected) {
                    RoomConnectionState.CONNECTED
                } else {
                    RoomConnectionState.RECONCILING
                },
                playback = remote,
                lastError = null
            )

            val local = player.snapshot()
            val pending = pendingAction
            if (pending != null && localActionIsNewer(pending, remote)) {
                shouldPublishLocal = true
                return@withLock
            }

            // An idle/ended/error player may retain the same media ID after its extracted
            // stream URL expires. Resolve a new local stream instead of reusing it.
            val localNeedsStreamRecovery = local.sourceId == remote.canonicalSourceUrl &&
                !local.isReady && !local.isBuffering
            val sameDeviceState = remote.updatedBy == userId &&
                local.sourceId == remote.canonicalSourceUrl &&
                local.playWhenReady == remote.isPlaying &&
                !localNeedsStreamRecovery
            if (sameDeviceState && pending == null) {
                lastAppliedRevision = maxOf(lastAppliedRevision, remote.revision)
                retryPolicy.recordSuccess(remote.revision)
                return@withLock
            }

            val changed = remote.revision > lastAppliedRevision &&
                (remote.actionType != RoomActionType.STATE ||
                    local.sourceId != remote.canonicalSourceUrl ||
                    local.playWhenReady != remote.isPlaying ||
                    localNeedsStreamRecovery)
            shouldApply = retryPolicy.shouldAttempt(
                revision = remote.revision,
                nowElapsedMs = clock.elapsedRealtimeMs(),
                hasUnappliedChanges = changed
            )
            if (!changed && remote.revision > lastAppliedRevision) {
                lastAppliedRevision = remote.revision
                retryPolicy.recordSuccess(remote.revision)
            }
        }

        if (shouldPublishLocal) {
            requestPublishAfterApplyLock(roomId, generation)
            return
        }
        if (!shouldApply || !isCurrent(roomId, generation)) return
        if (playbackPhase.isSystemPaused) {
            // Keep the authoritative row fresh but do not let a remote PLAY/START
            // override this device's active OS interruption. Resume reconciles it.
            recordDiagnostic(
                "remote.apply_deferred_system_pause",
                "revision=${remote.revision} action=${remote.actionType}"
            )
            return
        }

        try {
            applyRemoteSnapshot(remote, roomId, generation)
            retryPolicy.recordSuccess(remote.revision)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val retryIn = retryPolicy.recordFailure(
                remote.revision,
                clock.elapsedRealtimeMs()
            )
            setError(
                IOException(
                    "Shared track is not ready yet; retrying in ${retryIn / 1000L}s.",
                    error
                ),
                "Room playback is reconnecting"
            )
        }
    }

    /** Schedule a current local command after the reconciliation decision has released its mutex. */
    private fun requestPublishAfterApplyLock(roomId: String, generation: Long) {
        roomScope?.launch {
            applyMutex.withLock { }
            if (isCurrent(roomId, generation)) schedulePendingPublish(roomId, generation)
        }
    }

    private fun localActionIsNewer(
        pending: PendingLocalAction,
        remote: RoomPlaybackSnapshot
    ): Boolean {
        val localServerMs = clock.estimatedServerTimeMs() -
            (clock.elapsedRealtimeMs() - pending.elapsedMs).coerceAtLeast(0L)
        val remoteServerMs = parseTimestamp(
            remote.commandAt.ifBlank { remote.updatedAt }
        ) ?: return false
        return localServerMs > remoteServerMs + LOCAL_ACTION_WIN_MARGIN_MS
    }

    private suspend fun applyRemoteSnapshot(
        remote: RoomPlaybackSnapshot,
        roomId: String,
        generation: Long
    ) {
        if (!isCurrent(roomId, generation)) return
        // Duplicate poll/realtime wakes for the same revision share one in-flight resolution.
        if (!applyingRevisions.add(remote.revision)) return

        val applyStarted = clock.elapsedRealtimeMs()
        val commandTimestamp = parseTimestamp(remote.commandAt.ifBlank { remote.updatedAt })
        val commandAge = commandTimestamp?.let {
            (clock.estimatedServerTimeMs() - it).coerceAtLeast(0L)
        }
        recordDiagnostic(
            "player.apply_start",
            "revision=${remote.revision} action=${remote.actionType} " +
                "command_age=${commandAge?.let { "${it}ms" } ?: "unknown"}",
            commandAgeMs = commandAge
        )
        val localGeneration = localActionGeneration
        val startingSignal = signalSequence
        var shouldReconcileAgain = false
        var shouldPublishLocal = false
        var shouldStartBarrier = false
        try {
            if (remote.isPlaying && !clock.isCalibrated()) {
                try {
                    clock.calibrate()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Clock loss disables scheduled sync, not already-prepared local audio.
                    recordDiagnostic("clock.unavailable", "revision=${remote.revision} ${error.message.orEmpty()}")
                }
            }
            val before = player.snapshot()
            if (remote.actionType == RoomActionType.TRACK && remote.isPlaying) {
                // Stop the previous item immediately. New-track playback remains paused
                // until every currently active room member has prepared its own stream.
                player.pause()
            }
            // Never hold applyMutex across NewPipe, stream preparation or readiness waits.
            val needsFreshStream = before.sourceId != remote.canonicalSourceUrl ||
                (!before.isReady && !before.isBuffering)
            if (needsFreshStream) {
                val audio = mediaResolver.resolve(remote.canonicalSourceUrl)
                if (!isCurrent(roomId, generation) ||
                    localActionGeneration != localGeneration ||
                    signalSequence != startingSignal
                ) {
                    shouldReconcileAgain = true
                    return
                }
                player.prepareCanonicalSource(
                    canonicalSourceUrl = remote.canonicalSourceUrl,
                    streamUrl = audio.audioUrl,
                    title = audio.title,
                    artist = audio.artist,
                    mimeType = audio.mimeType
                )
                val ready = withTimeoutOrNull(TRACK_READY_TIMEOUT_MS) {
                    while (isCurrent(roomId, generation) &&
                        localActionGeneration == localGeneration &&
                        !player.snapshot().isReady
                    ) {
                        delay(100L)
                    }
                    player.snapshot().isReady
                } == true
                if (!ready) {
                    throw IOException(
                        "The shared track is still buffering. Nexus will retry without restarting the other phone."
                    )
                }
            }

            if (!isCurrent(roomId, generation) ||
                localActionGeneration != localGeneration ||
                signalSequence != startingSignal
            ) {
                shouldReconcileAgain = true
                return
            }

            // Re-read after potentially slow resolution. A newer revision always wins.
            val newest = repository.fetchPlaybackSnapshot(roomId)
            if (!isCurrent(roomId, generation)) return
            if (newest != null && newest.revision > remote.revision) {
                applyMutex.withLock {
                    if (newest.revision >= lastRemoteRevision) {
                        setRemotePlayback(newest)
                        lastRemoteRevision = newest.revision
                        mutableState.value = mutableState.value.copy(playback = newest)
                    }
                }
                shouldReconcileAgain = true
                return
            }

            applyMutex.withLock {
                if (!isCurrent(roomId, generation) ||
                    localActionGeneration != localGeneration ||
                    signalSequence != startingSignal ||
                    remote.revision < lastRemoteRevision ||
                    remote.revision < lastAppliedRevision
                ) {
                    shouldReconcileAgain = true
                    return@withLock
                }

                val latestIntent = pendingAction
                val authoritative = newest ?: remote
                if (latestIntent != null && localActionIsNewer(latestIntent, authoritative)) {
                    shouldPublishLocal = true
                    return@withLock
                }

                val anchorTime = parseTimestamp(
                    authoritative.commandAt.ifBlank { authoritative.updatedAt }
                )
                val elapsed = if (clock.isCalibrated()) {
                    anchorTime?.let {
                        (clock.estimatedServerTimeMs() - it).coerceAtLeast(0L)
                    } ?: 0L
                } else {
                    0L
                }
                val expected = RoomPlaybackMath.expectedRemotePositionMs(
                    positionMs = authoritative.positionMs,
                    isPlaying = authoritative.isPlaying,
                    elapsedSinceUpdateMs = elapsed,
                    durationMs = authoritative.durationMs
                )
                val isNewTrackStart = authoritative.actionType == RoomActionType.TRACK &&
                    authoritative.isPlaying
                // PLAY resumes an already-loaded item. READY/START is only for a new TRACK.
                val shouldUseSharedStart = isNewTrackStart
                if (startRevision != null && startRevision != authoritative.revision) {
                    cancelStartBarrier("a newer room revision arrived")
                }
                if (!isNewTrackStart && playbackAnchorRevision != authoritative.revision) {
                    playbackAnchorRevision = null
                    playbackAnchorAt = null
                    playbackAnchorPositionMs = null
                }
                val current = player.snapshot()
                if (shouldUseSharedStart) {
                    player.pause()
                    if (isNewTrackStart) player.seekTo(authoritative.positionMs)
                    shouldStartBarrier = true
                } else {
                    if (RoomPlaybackMath.needsSeek(current.positionMs, expected) ||
                        (!authoritative.isPlaying &&
                            current.playWhenReady &&
                            current.positionMs != expected)
                    ) {
                        player.seekTo(expected)
                    }
                    if (authoritative.isPlaying) player.play() else player.pause()
                }
                player.resetPlaybackSpeed()
                driftController.reset()
                driftSeekGate.reset()
                lastAppliedRevision = maxOf(lastAppliedRevision, authoritative.revision)
                setRemotePlayback(authoritative)
                mutableState.value = mutableState.value.copy(
                    roomId = roomId,
                    connection = RoomConnectionState.CONNECTED,
                    playback = authoritative,
                    lastError = null
                )
            }
        } finally {
            applyingRevisions.remove(remote.revision)
            recordDiagnostic(
                "player.apply_end",
                "revision=${remote.revision}",
                applyLatencyMs = (clock.elapsedRealtimeMs() - applyStarted).coerceAtLeast(0L)
            )
            if (shouldPublishLocal && isCurrent(roomId, generation)) {
                requestPublishAfterApplyLock(roomId, generation)
            }
            if (shouldStartBarrier && isCurrent(roomId, generation)) {
                beginPlaybackStartBarrier(
                    roomId = roomId,
                    generation = generation,
                    revision = remote.revision,
                    sourceId = remote.sourceId
                )
            }
            if (shouldReconcileAgain && isCurrent(roomId, generation)) {
                requestReconciliation(roomId, generation)
            }
        }
    }

    private fun beginPlaybackStartBarrier(
        roomId: String,
        generation: Long,
        revision: Long,
        sourceId: String
    ) {
        if (!isCurrent(roomId, generation) || revision <= completedStartRevision) return
        val remoteAtStart = lastRemotePlayback ?: return
        if (remoteAtStart.revision != revision || remoteAtStart.sourceId != sourceId) return
        val canonicalSourceUrl = remoteAtStart.canonicalSourceUrl
        if (startRevision == revision &&
            (startJob?.isActive == true || player.snapshot().playWhenReady)
        ) return

        clearStart()
        startRevision = revision
        scheduledStartAt = null
        playbackPhase = PlaybackPhase.START_PREPARING
        player.pause()
        val isNewTrackRevision = lastRemotePlayback?.let {
            it.revision == revision && it.actionType == RoomActionType.TRACK
        } == true
        if (isNewTrackRevision) player.seekTo(lastRemotePlayback?.positionMs ?: 0L)
        mutableState.value = mutableState.value.copy(
            lastError = null,
            statusMessage = "Preparing synchronized start…"
        )
        recordDiagnostic(
            "barrier.prepare",
            "revision=$revision source=$sourceId"
        )

        val scope = roomScope ?: return
        startJob = scope.launch {
            try {
                if (!clock.isCalibrated()) {
                    try {
                        clock.calibrate()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        recordDiagnostic("barrier.clock_probe_failed", error.message.orEmpty())
                    }
                    if (!clock.isCalibrated()) {
                        val local = player.snapshot()
                        if (remoteAtStart.isPlaying &&
                            local.sourceId == canonicalSourceUrl &&
                            local.isReady
                        ) {
                            startLocallyWithoutPeer(
                                roomId = roomId,
                                generation = generation,
                                revision = revision,
                                remote = remoteAtStart,
                                reason = "server_clock_unavailable"
                            )
                            return@launch
                        }
                        throw IOException("A calibrated server clock is required for a shared start.")
                    }
                }
                val barrierDeadline = clock.elapsedRealtimeMs() + BARRIER_READY_TIMEOUT_MS
                val localReadyDeadline = minOf(
                    barrierDeadline,
                    clock.elapsedRealtimeMs() + TRACK_READY_TIMEOUT_MS
                )
                // Wait for this device's own stream once. A peer that is not ready must
                // never cause repeated readiness RPCs or leave this device paused forever.
                while (
                    isCurrent(roomId, generation) &&
                    startRevision == revision &&
                    clock.elapsedRealtimeMs() < localReadyDeadline
                ) {
                    val local = player.snapshot()
                    if (local.sourceId == canonicalSourceUrl && local.isReady) break
                    delay(BARRIER_READY_RETRY_MS)
                }

                if (!isCurrent(roomId, generation) || startRevision != revision) return@launch
                val local = player.snapshot()
                if (local.sourceId != canonicalSourceUrl || !local.isReady) {
                    mutableState.value = mutableState.value.copy(
                        lastError = "This device could not prepare the selected track.",
                        statusMessage = null
                    )
                    recordDiagnostic("barrier.local_ready_timeout", "revision=$revision")
                    clearStart(cancelJob = false)
                    refreshPlaybackPhase()
                    return@launch
                }

                // One READY registration is enough: the database trigger broadcasts START
                // when the peer also becomes ready. Do not poll the readiness RPC.
                val result: RoomPlaybackReadyResult = repository.markPlaybackReady(
                    roomId = roomId,
                    revision = revision,
                    sourceId = sourceId
                )
                recordDiagnostic(
                    "barrier.ready",
                    "revision=$revision ready=${result.readyMembers}/${result.activeMembers}"
                )
                val scheduled = result.startAt
                if (!scheduled.isNullOrBlank()) {
                    onBarrierStartScheduled(
                        roomId = roomId,
                        generation = generation,
                        revision = revision,
                        sourceId = sourceId,
                        startAt = scheduled
                    )
                    return@launch
                }

                recordDiagnostic("barrier.waiting_for_peer", "revision=$revision")
                // A late peer may still emit a stale START after local fallback; the completed
                // RECOVERY revision prevents that older barrier from rewinding playback.
                while (
                    isCurrent(roomId, generation) &&
                    startRevision == revision &&
                    clock.elapsedRealtimeMs() < barrierDeadline
                ) {
                    delay(BARRIER_READY_RETRY_MS)
                }

                if (!isCurrent(roomId, generation) || startRevision != revision) return@launch

                // Re-check once before fallback in case the START broadcast was delayed.
                // This also refreshes the server readiness lease for a peer that is almost ready.
                val finalReady = try {
                    repository.markPlaybackReady(roomId, revision, sourceId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    recordDiagnostic("barrier.final_ready_check_failed", error.message.orEmpty())
                    null
                }
                val finalStartAt = finalReady?.startAt
                if (!finalStartAt.isNullOrBlank()) {
                    onBarrierStartScheduled(
                        roomId = roomId,
                        generation = generation,
                        revision = revision,
                        sourceId = sourceId,
                        startAt = finalStartAt
                    )
                    return@launch
                }

                val remote = lastRemotePlayback
                val readyLocal = player.snapshot()
                if (remote == null ||
                    remote.revision != revision ||
                    remote.sourceId != sourceId ||
                    !remote.isPlaying ||
                    readyLocal.sourceId != remote.canonicalSourceUrl ||
                    !readyLocal.isReady
                ) {
                    mutableState.value = mutableState.value.copy(
                        lastError = "The track is not ready to start on this device.",
                        statusMessage = null
                    )
                    recordDiagnostic("barrier.fallback_not_ready", "revision=$revision")
                    startRevision = null
                    return@launch
                }

                // Start from the saved track position now, then publish a fresh RECOVERY
                // command so late peers receive a new anchor instead of stale command time.
                startLocallyWithoutPeer(
                    roomId = roomId,
                    generation = generation,
                    revision = revision,
                    remote = remote,
                    reason = "peer_ready_timeout"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrent(roomId, generation) && startRevision == revision) {
                    setError(error, "Synchronized start could not be scheduled")
                    recordDiagnostic("barrier.error", error.message.orEmpty())
                    val remote = lastRemotePlayback
                    if (remote != null &&
                        remote.revision == revision &&
                        remote.sourceId == sourceId &&
                        remote.isPlaying
                    ) {
                        startLocallyWithoutPeer(
                            roomId = roomId,
                            generation = generation,
                            revision = revision,
                            remote = remote,
                            reason = "readiness_rpc_failed"
                        )
                    } else {
                        clearStart(cancelJob = false)
                        refreshPlaybackPhase()
                    }
                }
            }
        }
    }

    private fun startLocallyWithoutPeer(
        roomId: String,
        generation: Long,
        revision: Long,
        remote: RoomPlaybackSnapshot,
        reason: String
    ) {
        if (!isCurrent(roomId, generation) || startRevision != revision) return
        val local = player.snapshot()
        if (local.sourceId != remote.canonicalSourceUrl || !local.isReady || !remote.isPlaying) {
            mutableState.value = mutableState.value.copy(
                lastError = "This device is not ready to start the selected track.",
                statusMessage = null
            )
            recordDiagnostic("barrier.fallback_not_ready", "revision=$revision reason=$reason")
            clearStart(cancelJob = false)
            refreshPlaybackPhase()
            return
        }

        val position = remote.positionMs.coerceAtLeast(0L)
        player.seekTo(position)
        player.resetPlaybackSpeed()
        player.play()
        completedStartRevision = maxOf(completedStartRevision, revision)
        clearStart(cancelJob = false)
        playbackPhase = PlaybackPhase.ROOM_PLAYING
        playbackAnchorRevision = revision
        playbackAnchorAt = serverTimestamp()
        playbackAnchorPositionMs = position
        mutableState.value = mutableState.value.copy(
            connection = RoomConnectionState.CONNECTED,
            lastError = null,
            statusMessage = null
        )
        recordDiagnostic(
            "barrier.started_without_peer",
            "revision=$revision position=${position}ms reason=$reason"
        )
        publishFallbackRecovery(roomId, generation, remote, position)
    }

    private fun publishFallbackRecovery(
        roomId: String,
        generation: Long,
        remote: RoomPlaybackSnapshot,
        positionMs: Long
    ) {
        if (!isCurrent(roomId, generation) || userId == null ||
            pendingAction != null || lastRemoteRevision > remote.revision
        ) return
        setPendingAction(PendingLocalAction(
            actionId = UUID.randomUUID().toString(),
            generation = ++localActionGeneration,
            elapsedMs = clock.elapsedRealtimeMs(),
            actionType = RoomActionType.RECOVERY,
            sourceId = remote.sourceId,
            canonicalSourceUrl = remote.canonicalSourceUrl,
            title = remote.title,
            artist = remote.artist,
            shouldPlay = true,
            requestedPositionMs = positionMs
        ))
        schedulePendingPublish(roomId, generation)
    }

    private fun onBarrierStartScheduled(
        roomId: String,
        generation: Long,
        revision: Long,
        sourceId: String,
        startAt: String
    ) {
        if (!isCurrent(roomId, generation)) return
        val remote = lastRemotePlayback
        if (remote == null ||
            remote.revision != revision ||
            remote.sourceId != sourceId ||
            !remote.isPlaying
        ) return

        // Duplicate or stale STARTs cannot rewind a completed local timeline. A device
        // that fell back has published a newer RECOVERY revision for any late peer.
        if (revision <= completedStartRevision) {
            recordDiagnostic("barrier.duplicate_start_ignored", "revision=$revision")
            return
        }
        if (startRevision != revision) return

        val startAtMs = parseTimestamp(startAt) ?: run {
            recordDiagnostic("barrier.invalid_start", "revision=$revision")
            return
        }
        if (scheduledStartAt == startAt && startJob?.isActive == true) return

        startJob?.cancel()
        startJob = null
        scheduledStartAt = startAt
        playbackPhase = PlaybackPhase.START_SCHEDULED
        playbackAnchorRevision = revision
        playbackAnchorAt = startAt
        val scope = roomScope ?: return
        startJob = scope.launch {
            try {
                recordDiagnostic(
                    "barrier.start_scheduled",
                    "revision=$revision server_start=$startAt"
                )
                while (isCurrent(roomId, generation) && startRevision == revision) {
                    val remaining = startAtMs - clock.estimatedServerTimeMs()
                    if (remaining <= 0L) break
                    delay(remaining.coerceAtMost(250L))
                }
                if (!isCurrent(roomId, generation) ||
                    startRevision != revision ||
                    pendingAction != null
                ) return@launch

                val currentRemote = lastRemotePlayback
                if (currentRemote == null ||
                    currentRemote.revision != revision ||
                    currentRemote.sourceId != sourceId ||
                    !currentRemote.isPlaying
                ) return@launch

                val ready = withTimeoutOrNull(BARRIER_FINAL_READY_TIMEOUT_MS) {
                    while (isCurrent(roomId, generation) && !player.snapshot().isReady) {
                        delay(100L)
                    }
                    player.snapshot().isReady
                } == true
                if (!ready) {
                    mutableState.value = mutableState.value.copy(
                        lastError = "The local stream stopped being ready before the shared start.",
                        statusMessage = null
                    )
                    clearStart(cancelJob = false)
                    refreshPlaybackPhase()
                    recordDiagnostic("barrier.final_ready_timeout", "revision=$revision")
                    return@launch
                }

                // The new stream is staged paused until start_at. The 1.5s lead is not
                // playback time; only elapsed time after the scheduled start advances it.
                val positionAtStart = currentRemote.positionMs.coerceAtLeast(0L)
                val elapsedSinceStart = (
                    clock.estimatedServerTimeMs() - startAtMs
                ).coerceAtLeast(0L)
                val target = RoomPlaybackMath.expectedRemotePositionMs(
                    positionMs = positionAtStart,
                    isPlaying = true,
                    elapsedSinceUpdateMs = elapsedSinceStart,
                    durationMs = currentRemote.durationMs
                )
                player.seekTo(target)
                player.resetPlaybackSpeed()
                if (!isCurrent(roomId, generation) ||
                    startRevision != revision ||
                    pendingAction != null
                ) return@launch
                player.play()
                // Mark the scheduled start complete. A delayed duplicate START must not
                // create a second start job; only a device that used the no-peer fallback
                // remains eligible for a late peer's shared-anchor correction.
                completedStartRevision = revision
                clearStart(cancelJob = false)
                playbackPhase = PlaybackPhase.ROOM_PLAYING
                playbackAnchorRevision = revision
                playbackAnchorAt = startAt
                playbackAnchorPositionMs = positionAtStart
                mutableState.value = mutableState.value.copy(
                    connection = RoomConnectionState.CONNECTED,
                    lastError = null,
                    statusMessage = null
                )
                recordDiagnostic(
                    "barrier.started",
                    "revision=$revision start_offset=${elapsedSinceStart}ms"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrent(roomId, generation) && startRevision == revision) {
                    setError(error, "Synchronized playback could not start")
                    recordDiagnostic("barrier.start_error", error.message.orEmpty())
                    val remote = lastRemotePlayback
                    if (remote != null &&
                        remote.revision == revision &&
                        remote.sourceId == sourceId &&
                        remote.isPlaying
                    ) {
                        startLocallyWithoutPeer(
                            roomId = roomId,
                            generation = generation,
                            revision = revision,
                            remote = remote,
                            reason = "scheduled_start_failed"
                        )
                    } else {
                        clearStart(cancelJob = false)
                        refreshPlaybackPhase()
                    }
                }
            }
        }
    }

    private fun cancelStartBarrier(reason: String) {
        if (startRevision == null) return
        recordDiagnostic("barrier.cancelled", "revision=$startRevision reason=$reason")
        clearStart()
        refreshPlaybackPhase()
        mutableState.value = mutableState.value.copy(statusMessage = null)
    }

    private fun resumeAfterInterruption(
        roomId: String,
        remote: RoomPlaybackSnapshot?,
        source: String,
        requirePlayWhenReady: Boolean
    ) {
        val local = player.snapshot()
        if (remote?.isPlaying == true && (!requirePlayWhenReady || local.playWhenReady)) {
            rejoinRoomTimelineLocally(roomId, remote, source)
            return
        }
        if (remote != null && !remote.isPlaying && local.sourceId == remote.canonicalSourceUrl) {
            player.seekTo(expectedRoomPosition(remote))
            lastAppliedRevision = maxOf(lastAppliedRevision, remote.revision)
            retryPolicy.recordSuccess(remote.revision)
        } else if (remote != null && local.sourceId != remote.canonicalSourceUrl) {
            requestReconciliation(roomId, sessionGeneration)
        }
        player.pause()
        recordDiagnostic(
            "local.system_resume_held",
            "room is paused or local play intent was cleared; no room publish"
        )
    }

    private fun rejoinRoomTimelineLocally(
        roomId: String,
        remote: RoomPlaybackSnapshot,
        source: String
    ) {
        val local = player.snapshot()
        if (local.sourceId != remote.canonicalSourceUrl) {
            player.pause()
            recordDiagnostic(
                "local.system_pause_reconcile",
                "local source differs from room revision=${remote.revision}"
            )
            requestReconciliation(roomId, sessionGeneration)
            return
        }
        clearStart()
        completedStartRevision = maxOf(completedStartRevision, remote.revision)
        playbackPhase = PlaybackPhase.ROOM_PLAYING
        lastAppliedRevision = maxOf(lastAppliedRevision, remote.revision)
        retryPolicy.recordSuccess(remote.revision)
        mutableState.value = mutableState.value.copy(
            playback = remote,
            statusMessage = null
        )
        val expected = expectedRoomPosition(remote)
        player.seekTo(expected)
        player.resetPlaybackSpeed()
        player.play()
        recordDiagnostic(
            "local.system_pause_rejoined",
            "source=$source revision=${remote.revision} position=${expected}ms; no room publish"
        )
    }

    private fun expectedRoomPosition(remote: RoomPlaybackSnapshot): Long {
        val anchorTime = if (playbackAnchorRevision == remote.revision) {
            playbackAnchorAt?.let(::parseTimestamp)
        } else {
            null
        } ?: parseTimestamp(remote.commandAt.ifBlank { remote.updatedAt })
        val elapsed = if (clock.isCalibrated()) {
            anchorTime?.let {
                (clock.estimatedServerTimeMs() - it).coerceAtLeast(0L)
            } ?: 0L
        } else {
            0L
        }
        val anchorPosition = if (playbackAnchorRevision == remote.revision) {
            playbackAnchorPositionMs ?: remote.positionMs
        } else {
            remote.positionMs
        }
        return RoomPlaybackMath.expectedRemotePositionMs(
            positionMs = anchorPosition,
            isPlaying = remote.isPlaying,
            elapsedSinceUpdateMs = elapsed,
            durationMs = remote.durationMs
        )
    }

    private fun correctLocalDrift(remote: RoomPlaybackSnapshot) {
        val roomId = activeRoomId ?: return
        val local = player.snapshot()
        if (!isCurrent(roomId, sessionGeneration)) return
        if (playbackPhase.isSystemPaused) return
        if (local.sourceId != remote.canonicalSourceUrl || pendingAction != null) return
        if (!remote.isPlaying) {
            player.resetPlaybackSpeed()
            driftController.reset()
            driftSeekGate.reset()
            return
        }
        if (local.isBuffering || !local.isPlaying) return

        if (!clock.isCalibrated()) return
        val nowElapsed = clock.elapsedRealtimeMs()
        if (driftSeekGate.shouldWait(
                localPositionMs = local.positionMs,
                isBuffering = local.isBuffering,
                isPlaying = local.isPlaying,
                remoteIsPlaying = remote.isPlaying,
                nowElapsedMs = nowElapsed
            )
        ) return

        val expected = expectedRoomPosition(remote)
        val drift = local.positionMs - expected
        val correction = driftController.evaluate(
            driftMs = drift,
            isBuffering = local.isBuffering,
            nowMs = nowElapsed,
            roundTripMs = clock.estimatedRoundTripTimeMs()
        )
        if (kotlin.math.abs(drift) >= 100L || correction.type != DriftCorrectionType.NONE) {
            recordDiagnostic("drift.correction", "type=${correction.type} rtt=${clock.estimatedRoundTripTimeMs()}ms", driftMs = drift)
        }
        when (correction.type) {
            DriftCorrectionType.NONE -> Unit
            DriftCorrectionType.SET_SPEED -> player.setPlaybackSpeed(correction.speed)
            DriftCorrectionType.RESET_SPEED -> player.resetPlaybackSpeed()
            DriftCorrectionType.SEEK -> {
                player.resetPlaybackSpeed()
                player.seekTo(expected)
                driftSeekGate.begin(expected, nowElapsed)
            }
        }
    }

    private fun setError(error: Exception, prefix: String) {
        if (error is CancellationException) throw error
        val message = error.message?.takeIf(String::isNotBlank) ?: "Please try again."
        mutableState.value = mutableState.value.copy(
            lastError = "$prefix · $message",
            statusMessage = null
        )
    }

    private fun parseTimestamp(value: String): Long? = RoomTimestampParser.parse(value)

    private fun serverTimestamp(): String {
        val formatter = SERVER_TIMESTAMP_FORMATTER.get() ?: SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            Locale.US
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.also(SERVER_TIMESTAMP_FORMATTER::set)
        return formatter.format(Date(clock.estimatedServerTimeMs()))
    }

    private companion object {
        val SERVER_TIMESTAMP_FORMATTER = ThreadLocal<SimpleDateFormat>()
        const val HEARTBEAT_INTERVAL_MS = 25_000L
        const val CLOCK_CALIBRATION_INTERVAL_MS = 300_000L
        const val TRACK_READY_TIMEOUT_MS = 4_000L
        const val BARRIER_READY_TIMEOUT_MS = 3_000L
        const val BARRIER_READY_RETRY_MS = 100L
        const val BARRIER_FINAL_READY_TIMEOUT_MS = 5_000L
        const val LOCAL_TRACK_FALLBACK_TIMEOUT_MS = 3_000L
        const val LOCAL_ACTION_WIN_MARGIN_MS = 200L
        const val PUBLISH_RETRY_BASE_MS = 500L
        const val PUBLISH_RETRY_MAX_MS = 8_000L
        const val MAX_DIAGNOSTIC_EVENTS = 120
    }
}
