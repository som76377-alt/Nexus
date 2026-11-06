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
import com.nexus.music.rooms.domain.RoomPublishResult
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

    private var roomJob: Job? = null
    private var roomScope: CoroutineScope? = null
    private var reconcileJob: Job? = null
    private var reconcileRequested = false
    private var publishJob: Job? = null

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
        val artist: String?
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
        mutableDiagnostics.value = RoomDiagnosticsSnapshot()
        recordDiagnostic("room.attach", "room=$roomId")
        mutableState.value = RoomControllerState(
            roomId = roomId,
            connection = RoomConnectionState.CONNECTING
        )
        pendingAction = null
        lastRemoteRevision = 0L
        lastAppliedRevision = 0L
        lastRemotePlayback = null
        retryPolicy.reset()
        driftController.reset()
        driftSeekGate.reset()
        signalSequence = 0L

        val newUserId = repository.currentUserId()
        if (!isCurrent(roomId, generation)) return
        userId = newUserId

        // Keep the last valid clock estimate if a network probe temporarily fails.
        runCatching { clock.calibrate() }
        if (!isCurrent(roomId, generation)) return

        val initial = repository.fetchPlaybackSnapshot(roomId)
        if (!isCurrent(roomId, generation)) return
        if (initial != null) {
            lastRemotePlayback = initial
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
                    RoomRealtimeSignal.ChatInvalidated -> {
                        mutableState.value = mutableState.value.copy(
                            chatSignalVersion = mutableState.value.chatSignalVersion + 1L
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
                        artist = local.artist
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
        userId = null
        pendingAction = null
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

    override suspend fun submit(command: RoomCommand): RoomPublishResult {
        val activeId = activeRoomId
            ?: throw IOException("There is no active listening room.")
        require(command.roomId == activeId) { "Command belongs to a different room." }
        val result = repository.publishPlayback(command)
        if (result.applied) {
            lastRemoteRevision = maxOf(lastRemoteRevision, result.revision)
            lastAppliedRevision = maxOf(lastAppliedRevision, result.revision)
        } else {
            requestReconciliation(activeId, sessionGeneration)
        }
        return result
    }

    fun close() {
        stopRoomSession()
        activeRoomId = null
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
        publishJob?.cancel()
        publishJob = null
    }

    private fun isCurrent(roomId: String, generation: Long): Boolean =
        activeRoomId == roomId && sessionGeneration == generation

    private fun recordDiagnostic(
        event: String,
        detail: String = "",
        publishLatencyMs: Long? = null,
        snapshotLatencyMs: Long? = null,
        applyLatencyMs: Long? = null,
        driftMs: Long? = null
    ) {
        val elapsed = clock.elapsedRealtimeMs()
        val wall = System.currentTimeMillis()
        val line = "$wall +${elapsed}ms $event" +
            detail.takeIf(String::isNotBlank)?.let { " | $it" }.orEmpty()
        val previous = mutableDiagnostics.value
        mutableDiagnostics.value = previous.copy(
            lastPublishLatencyMs = publishLatencyMs ?: previous.lastPublishLatencyMs,
            lastSnapshotLatencyMs = snapshotLatencyMs ?: previous.lastSnapshotLatencyMs,
            lastApplyLatencyMs = applyLatencyMs ?: previous.lastApplyLatencyMs,
            lastDriftMs = driftMs ?: previous.lastDriftMs,
            events = (previous.events + line).takeLast(MAX_DIAGNOSTIC_EVENTS)
        )
    }

    private fun captureLocalIntent(intent: RoomPlayerIntent) {
        val roomId = activeRoomId ?: return
        when (intent) {
            is RoomPlayerIntent.SkipToNext -> {
                captureSkipIntent(
                    roomId,
                    intent.previousSourceId,
                    intent.previousPositionMs,
                    next = true
                )
                return
            }
            is RoomPlayerIntent.SkipToPrevious -> {
                captureSkipIntent(
                    roomId,
                    intent.previousSourceId,
                    intent.previousPositionMs,
                    next = false
                )
                return
            }
            else -> Unit
        }
        val elapsed = clock.elapsedRealtimeMs()
        val current = player.snapshot()
        val previous = pendingAction
        // Merge Play/Pause/Seek into a pending Track only when Media3 already exposes
        // that exact canonical item. No time-window guess is needed.
        val preserveTrack = previous?.actionType == RoomActionType.TRACK &&
            current.sourceId.isNotBlank() &&
            previous.sourceId == current.sourceId

        val next = when (intent) {
            is RoomPlayerIntent.SelectTrack -> PendingLocalAction(
                actionId = previous?.takeIf { preserveTrack }?.actionId
                    ?: UUID.randomUUID().toString(),
                generation = ++localActionGeneration,
                elapsedMs = elapsed,
                actionType = RoomActionType.TRACK,
                sourceId = intent.sourceId,
                canonicalSourceUrl = intent.canonicalSourceUrl,
                title = intent.title,
                artist = intent.artist
            )
            RoomPlayerIntent.Play -> if (preserveTrack) {
                previous!!.copy(generation = ++localActionGeneration, elapsedMs = elapsed)
            } else {
                pendingFromSnapshot(RoomActionType.PLAY, current, elapsed)
            }
            RoomPlayerIntent.Pause -> if (preserveTrack) {
                previous!!.copy(generation = ++localActionGeneration, elapsedMs = elapsed)
            } else {
                pendingFromSnapshot(RoomActionType.PAUSE, current, elapsed)
            }
            is RoomPlayerIntent.SeekTo -> if (preserveTrack) {
                previous!!.copy(generation = ++localActionGeneration, elapsedMs = elapsed)
            } else {
                pendingFromSnapshot(RoomActionType.SEEK, current, elapsed)
            }
            is RoomPlayerIntent.SkipToNext,
            is RoomPlayerIntent.SkipToPrevious -> null
        } ?: return

        pendingAction = next
        recordDiagnostic("local.intent", "type=${next.actionType} source=${next.sourceId} generation=${next.generation}")
        val scope = roomScope ?: return
        if (publishJob?.isActive == true) return
        publishJob = scope.launch {
            delay(100L)
            publishPendingAction(roomId, sessionGeneration)
        }
    }

    private fun captureSkipIntent(
        roomId: String,
        previousSourceId: String,
        previousPositionMs: Long,
        next: Boolean
    ) {
        val scope = roomScope ?: return
        val session = sessionGeneration
        val generation = ++localActionGeneration
        recordDiagnostic(
            "local.skip_requested",
            "direction=${if (next) "next" else "previous"} source=$previousSourceId"
        )
        scope.launch {
            // ForwardingPlayer records before delegating. Observe the resulting Media3
            // timeline after its synchronous command has completed.
            delay(SKIP_OBSERVE_DELAY_MS)
            if (!isCurrent(roomId, session) || localActionGeneration != generation) return@launch
            val after = player.snapshot()
            val intent = if (after.sourceId.isNotBlank() && after.sourceId != previousSourceId) {
                RoomPlayerIntent.SelectTrack(
                    sourceId = after.sourceId,
                    canonicalSourceUrl = after.canonicalSourceUrl.ifBlank { after.sourceId },
                    title = after.title,
                    artist = after.artist
                )
            } else if (after.positionMs != previousPositionMs) {
                RoomPlayerIntent.SeekTo(after.positionMs)
            } else {
                return@launch
            }
            captureLocalIntent(intent)
        }
    }

    private fun pendingFromSnapshot(
        type: RoomActionType,
        snapshot: com.nexus.music.rooms.domain.LocalPlaybackSnapshot,
        elapsed: Long
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
            artist = snapshot.artist
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
                runCatching { clock.calibrate() }
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

            try {
                publishPendingAction(roomId, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed = true
                setError(error, "Playback command could not be published")
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

    private fun requestReconciliation(roomId: String, generation: Long) {
        if (!isCurrent(roomId, generation)) return
        reconcileRequested = true
        if (reconcileJob?.isActive == true) return
        val scope = roomScope ?: return
        reconcileJob = scope.launch {
            do {
                reconcileRequested = false
                try {
                    refreshRemotePlayback(roomId, generation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    setError(error, "Playback reconnecting")
                }
            } while (
                reconcileRequested &&
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
                // The database timeline advances only when the audio is actually advancing.
                // Shared state represents the requested play state, not transient audio advancement.
                // A buffering player may have isPlaying=false while playWhenReady=true.
                isPlaying = local.playWhenReady,
                positionMs = local.positionMs.coerceAtLeast(0L),
                durationMs = local.durationMs.coerceAtLeast(0L)
            )

            val publishStarted = clock.elapsedRealtimeMs()
            val result = repository.publishPlayback(command)
            val publishLatency = (clock.elapsedRealtimeMs() - publishStarted).coerceAtLeast(0L)
            recordDiagnostic(
                "rpc.publish",
                "type=${pending.actionType} applied=${result.applied} revision=${result.revision}",
                publishLatencyMs = publishLatency
            )
            if (!isCurrent(roomId, generation)) return
            if (result.applied) {
                acceptPublished(command, result.revision, pending)
                return
            }

            val latest = repository.fetchPlaybackSnapshot(roomId) ?: return
            if (!isCurrent(roomId, generation)) return
            lastRemotePlayback = latest
            lastRemoteRevision = maxOf(lastRemoteRevision, latest.revision)
            val localActionServerMs = clock.estimatedServerTimeMs() -
                (clock.elapsedRealtimeMs() - pending.elapsedMs).coerceAtLeast(0L)
            val remoteActionServerMs = parseTimestamp(
                latest.commandAt.ifBlank { latest.updatedAt }
            ) ?: Long.MAX_VALUE
            val localStillCurrent = pendingAction?.generation == pending.generation
            val localWins = localStillCurrent &&
                localActionServerMs > remoteActionServerMs + LOCAL_ACTION_WIN_MARGIN_MS

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
            lastRemotePlayback = authoritative
            lastRemoteRevision = maxOf(lastRemoteRevision, authoritative.revision)
            if (!localWins) {
                pendingAction = null
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
        if (pendingAction?.generation == pending.generation) {
            pendingAction = null
        }
        mutableState.value = mutableState.value.copy(
            connection = RoomConnectionState.CONNECTED,
            lastError = null
        )
    }

    private suspend fun refreshRemotePlayback(roomId: String, generation: Long) {
        if (!isCurrent(roomId, generation)) return
        // Network IO is deliberately outside applyMutex. A slow REST fetch must not block
        // a newer pause/seek or another reconciliation from entering the decision path.
        val snapshotStarted = clock.elapsedRealtimeMs()
        val remote = repository.fetchPlaybackSnapshot(roomId) ?: return
        val snapshotLatency = (clock.elapsedRealtimeMs() - snapshotStarted).coerceAtLeast(0L)
        recordDiagnostic(
            "rest.snapshot",
            "revision=${remote.revision}",
            snapshotLatencyMs = snapshotLatency
        )
        if (!isCurrent(roomId, generation)) return

        var shouldApply = false
        var shouldPublishLocal = false
        applyMutex.withLock {
            if (!isCurrent(roomId, generation) || remote.revision < lastRemoteRevision) {
                return@withLock
            }
            lastRemotePlayback = remote
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
            delay(50L)
            if (isCurrent(roomId, generation)) publishPendingAction(roomId, generation)
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
        recordDiagnostic(
            "player.apply_start",
            "revision=${remote.revision} action=${remote.actionType}"
        )
        val localGeneration = localActionGeneration
        val startingSignal = signalSequence
        var shouldReconcileAgain = false
        var shouldPublishLocal = false
        try {
            val before = player.snapshot()
            // Never hold applyMutex across NewPipe, stream preparation or readiness waits.
            val needsFreshStream = before.sourceId != remote.canonicalSourceUrl ||
                (!before.isReady && !before.isBuffering)
            if (needsFreshStream) {
                val audio = mediaResolver.resolve(
                    remote.sourceId,
                    remote.canonicalSourceUrl
                )
                if (!isCurrent(roomId, generation) ||
                    localActionGeneration != localGeneration ||
                    signalSequence != startingSignal
                ) {
                    shouldReconcileAgain = true
                    return
                }
                player.prepareCanonicalSource(
                    sourceId = remote.sourceId,
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
                        lastRemotePlayback = newest
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
                val elapsed = anchorTime?.let {
                    (clock.estimatedServerTimeMs() - it).coerceAtLeast(0L)
                } ?: 0L
                val expected = RoomPlaybackMath.expectedRemotePositionMs(
                    positionMs = authoritative.positionMs,
                    isPlaying = authoritative.isPlaying,
                    elapsedSinceUpdateMs = elapsed,
                    durationMs = authoritative.durationMs
                )
                val current = player.snapshot()
                if (RoomPlaybackMath.needsSeek(current.positionMs, expected) ||
                    (!authoritative.isPlaying &&
                        current.playWhenReady &&
                        current.positionMs != expected)
                ) {
                    player.seekTo(expected)
                }
                player.resetPlaybackSpeed()
                driftController.reset()
                driftSeekGate.reset()
                if (authoritative.isPlaying) player.play() else player.pause()
                lastAppliedRevision = maxOf(lastAppliedRevision, authoritative.revision)
                lastRemotePlayback = authoritative
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
            if (shouldReconcileAgain && isCurrent(roomId, generation)) {
                requestReconciliation(roomId, generation)
            }
        }
    }

    private fun correctLocalDrift(remote: RoomPlaybackSnapshot) {
        val roomId = activeRoomId ?: return
        val local = player.snapshot()
        if (!isCurrent(roomId, sessionGeneration)) return
        if (local.sourceId != remote.canonicalSourceUrl || pendingAction != null) return
        if (!remote.isPlaying) {
            player.resetPlaybackSpeed()
            driftController.reset()
            driftSeekGate.reset()
            return
        }
        if (local.isBuffering || !local.isPlaying) return

        val nowElapsed = clock.elapsedRealtimeMs()
        if (driftSeekGate.shouldWait(
                localPositionMs = local.positionMs,
                isBuffering = local.isBuffering,
                isPlaying = local.isPlaying,
                remoteIsPlaying = remote.isPlaying,
                nowElapsedMs = nowElapsed
            )
        ) return

        val anchorTime = parseTimestamp(remote.commandAt.ifBlank { remote.updatedAt })
        val elapsed = anchorTime?.let {
            (clock.estimatedServerTimeMs() - it).coerceAtLeast(0L)
        } ?: 0L
        val expected = RoomPlaybackMath.expectedRemotePositionMs(
            positionMs = remote.positionMs,
            isPlaying = remote.isPlaying,
            elapsedSinceUpdateMs = elapsed,
            durationMs = remote.durationMs
        )
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
        mutableState.value = mutableState.value.copy(lastError = "$prefix · $message")
    }

    private fun parseTimestamp(value: String): Long? = RoomTimestampParser.parse(value)

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 25_000L
        const val CLOCK_CALIBRATION_INTERVAL_MS = 300_000L
        const val TRACK_READY_TIMEOUT_MS = 20_000L
        const val SKIP_OBSERVE_DELAY_MS = 120L
        const val LOCAL_ACTION_WIN_MARGIN_MS = 200L
        const val MAX_DIAGNOSTIC_EVENTS = 120
    }
}
