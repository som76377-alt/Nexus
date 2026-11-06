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

    private var roomJob: Job? = null
    private var roomScope: CoroutineScope? = null
    private var reconcileJob: Job? = null
    private var reconcileRequested = false
    private var publishJob: Job? = null
    private var barrierJob: Job? = null
    private var barrierStartJob: Job? = null
    private var barrierRevision: Long? = null
    private var barrierScheduledStartAt: String? = null
    private var barrierCompletedRevision: Long = 0L
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
        val shouldPlay: Boolean = false
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
        barrierRevision = null
        barrierCompletedRevision = 0L
        playbackAnchorRevision = null
        playbackAnchorAt = null
        playbackAnchorPositionMs = null

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
                    is RoomRealtimeSignal.PlaybackUpdated -> {
                        signalSequence++
                        recordDiagnostic(
                            "realtime.playback_snapshot",
                            "revision=${signal.snapshot.revision} sequence=$signalSequence"
                        )
                        refreshRemotePlayback(roomId, generation, signal.snapshot)
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
        barrierJob?.cancel()
        barrierJob = null
        barrierStartJob?.cancel()
        barrierStartJob = null
        barrierRevision = null
        barrierScheduledStartAt = null
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

    private fun captureLocalIntent(intent: RoomPlayerIntent) {
        val roomId = activeRoomId ?: return
        if (intent is RoomPlayerIntent.SelectTrack && intent.shouldPlay) {
            // Stop the previous item before the selected item can inherit playWhenReady.
            // Keep the requested state in the pending TRACK command for the shared barrier.
            player.pause()
        }
        cancelStartBarrier("new local playback intent")
        playbackAnchorRevision = null
        playbackAnchorAt = null
        playbackAnchorPositionMs = null
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
                artist = intent.artist,
                shouldPlay = intent.shouldPlay
            )
            RoomPlayerIntent.Play -> if (preserveTrack) {
                previous!!.copy(
                    generation = ++localActionGeneration,
                    elapsedMs = elapsed,
                    shouldPlay = true
                )
            } else {
                pendingFromSnapshot(RoomActionType.PLAY, current, elapsed)
            }
            RoomPlayerIntent.Pause -> if (preserveTrack) {
                previous!!.copy(
                    generation = ++localActionGeneration,
                    elapsedMs = elapsed,
                    shouldPlay = false
                )
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
            // The MediaSession wrapper emits after the synchronous skip command.
            // Yield for the collector; do not infer intent from an arbitrary time window.
            kotlinx.coroutines.yield()
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
            artist = snapshot.artist,
            shouldPlay = when (type) {
                RoomActionType.PLAY -> true
                RoomActionType.PAUSE -> false
                else -> snapshot.playWhenReady
            }
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
                // New tracks start at zero; never inherit the previous item's position.
                // The requested play state is retained while the local player stays paused.
                isPlaying = when (pending.actionType) {
                    RoomActionType.TRACK, RoomActionType.PLAY -> pending.shouldPlay
                    RoomActionType.PAUSE -> false
                    else -> local.playWhenReady
                },
                positionMs = if (pending.actionType == RoomActionType.TRACK) {
                    0L
                } else {
                    local.positionMs.coerceAtLeast(0L)
                },
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
        lastRemotePlayback = publishedSnapshot
        if (command.actionType != RoomActionType.TRACK) {
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
        if (command.isPlaying &&
            command.actionType in setOf(RoomActionType.TRACK, RoomActionType.PLAY)
        ) {
            beginPlaybackStartBarrier(
                roomId = command.roomId,
                generation = sessionGeneration,
                revision = revision,
                sourceId = command.canonicalSourceUrl
            )
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
                val isNewTrackStart = authoritative.actionType == RoomActionType.TRACK &&
                    authoritative.isPlaying
                val shouldUseSharedStart = authoritative.isPlaying &&
                    authoritative.actionType in setOf(RoomActionType.TRACK, RoomActionType.PLAY)
                if (barrierRevision != null && barrierRevision != authoritative.revision) {
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
                    if (isNewTrackStart) player.seekTo(0L)
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
            if (shouldStartBarrier && isCurrent(roomId, generation)) {
                beginPlaybackStartBarrier(
                    roomId = roomId,
                    generation = generation,
                    revision = remote.revision,
                    sourceId = remote.canonicalSourceUrl
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
        if (!isCurrent(roomId, generation) || revision <= barrierCompletedRevision) return
        if (barrierRevision == revision &&
            (barrierJob?.isActive == true || player.snapshot().playWhenReady)
        ) return

        barrierJob?.cancel()
        barrierStartJob?.cancel()
        barrierStartJob = null
        barrierRevision = revision
        barrierScheduledStartAt = null
        player.pause()
        player.seekTo(0L)
        mutableState.value = mutableState.value.copy(
            lastError = null,
            statusMessage = "Preparing synchronized start…"
        )
        recordDiagnostic(
            "barrier.prepare",
            "revision=$revision source=$sourceId"
        )

        val scope = roomScope ?: return
        barrierJob = scope.launch {
            val localReadyDeadline = clock.elapsedRealtimeMs() + TRACK_READY_TIMEOUT_MS
            try {
                // Wait for this device's own stream once. A peer that is not ready must
                // never cause repeated readiness RPCs or leave this device paused forever.
                while (
                    isCurrent(roomId, generation) &&
                    barrierRevision == revision &&
                    clock.elapsedRealtimeMs() < localReadyDeadline
                ) {
                    val local = player.snapshot()
                    if (local.sourceId == sourceId && local.isReady) break
                    delay(BARRIER_READY_RETRY_MS)
                }

                if (!isCurrent(roomId, generation) || barrierRevision != revision) return@launch
                val local = player.snapshot()
                if (local.sourceId != sourceId || !local.isReady) {
                    mutableState.value = mutableState.value.copy(
                        lastError = "This device could not prepare the selected track.",
                        statusMessage = null
                    )
                    recordDiagnostic("barrier.local_ready_timeout", "revision=$revision")
                    barrierRevision = null
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
                // The server expires readiness rows after 45 seconds. Wait slightly
                // longer before fallback so a late peer cannot schedule a shared start
                // after this device has already started independently.
                val peerDeadline = clock.elapsedRealtimeMs() + BARRIER_READY_TIMEOUT_MS
                while (
                    isCurrent(roomId, generation) &&
                    barrierRevision == revision &&
                    clock.elapsedRealtimeMs() < peerDeadline
                ) {
                    delay(BARRIER_READY_RETRY_MS)
                }

                if (!isCurrent(roomId, generation) || barrierRevision != revision) return@launch

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
                    remote.canonicalSourceUrl != sourceId ||
                    !remote.isPlaying ||
                    readyLocal.sourceId != sourceId ||
                    !readyLocal.isReady
                ) {
                    mutableState.value = mutableState.value.copy(
                        lastError = "The track is not ready to start on this device.",
                        statusMessage = null
                    )
                    recordDiagnostic("barrier.fallback_not_ready", "revision=$revision")
                    barrierRevision = null
                    return@launch
                }

                // The peer did not become ready in time. Start at the authoritative
                // track-command timeline so a later joiner can calculate the same offset.
                val commandAt = parseTimestamp(remote.commandAt.ifBlank { remote.updatedAt })
                val elapsed = commandAt?.let {
                    (clock.estimatedServerTimeMs() - it).coerceAtLeast(0L)
                } ?: 0L
                val target = RoomPlaybackMath.expectedRemotePositionMs(
                    positionMs = remote.positionMs,
                    isPlaying = true,
                    elapsedSinceUpdateMs = elapsed,
                    durationMs = remote.durationMs
                )
                player.seekTo(target)
                player.resetPlaybackSpeed()
                player.play()
                barrierCompletedRevision = revision
                barrierRevision = null
                barrierScheduledStartAt = null
                playbackAnchorRevision = revision
                playbackAnchorAt = remote.commandAt.ifBlank { remote.updatedAt }
                playbackAnchorPositionMs = remote.positionMs
                mutableState.value = mutableState.value.copy(
                    connection = RoomConnectionState.CONNECTED,
                    lastError = null,
                    statusMessage = null
                )
                recordDiagnostic("barrier.started_without_peer", "revision=$revision offset=${target}ms")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrent(roomId, generation) && barrierRevision == revision) {
                    setError(error, "Synchronized start could not be scheduled")
                    recordDiagnostic("barrier.error", error.message.orEmpty())
                    barrierRevision = null
                }
            }
        }
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
        if (remote != null &&
            (remote.revision != revision || remote.canonicalSourceUrl != sourceId || !remote.isPlaying)
        ) return

        // A START may arrive after this device used its bounded no-peer fallback.
        // It is then a peer-join signal, not a command to rewind the already-running track.
        if (revision == barrierCompletedRevision) {
            val local = player.snapshot()
            if (local.sourceId != sourceId || !local.isPlaying || remote == null) return
            val commandAt = parseTimestamp(remote.commandAt.ifBlank { remote.updatedAt })
            val elapsed = commandAt?.let {
                (clock.estimatedServerTimeMs() - it).coerceAtLeast(0L)
            } ?: 0L
            val expected = RoomPlaybackMath.expectedRemotePositionMs(
                positionMs = remote.positionMs,
                isPlaying = true,
                elapsedSinceUpdateMs = elapsed,
                durationMs = remote.durationMs
            )
            if (RoomPlaybackMath.needsSeek(local.positionMs, expected)) {
                player.resetPlaybackSpeed()
                player.seekTo(expected)
            }
            playbackAnchorRevision = revision
            playbackAnchorAt = remote.commandAt.ifBlank { remote.updatedAt }
            playbackAnchorPositionMs = remote.positionMs
            recordDiagnostic("barrier.late_shared_anchor", "revision=$revision offset=${expected}ms")
            return
        }
        if (barrierRevision != revision || revision < barrierCompletedRevision) return

        val startAtMs = parseTimestamp(startAt) ?: run {
            recordDiagnostic("barrier.invalid_start", "revision=$revision")
            return
        }
        if (barrierScheduledStartAt == startAt && barrierStartJob?.isActive == true) return

        barrierJob?.cancel()
        barrierJob = null
        barrierScheduledStartAt = startAt
        playbackAnchorRevision = revision
        playbackAnchorAt = startAt
        val scope = roomScope ?: return
        barrierStartJob?.cancel()
        barrierStartJob = scope.launch {
            try {
                recordDiagnostic(
                    "barrier.start_scheduled",
                    "revision=$revision server_start=$startAt"
                )
                while (isCurrent(roomId, generation) && barrierRevision == revision) {
                    val remaining = startAtMs - clock.estimatedServerTimeMs()
                    if (remaining <= 0L) break
                    delay(remaining.coerceAtMost(250L))
                }
                if (!isCurrent(roomId, generation) ||
                    barrierRevision != revision ||
                    pendingAction != null
                ) return@launch

                val currentRemote = lastRemotePlayback
                if (currentRemote == null ||
                    currentRemote.revision != revision ||
                    currentRemote.canonicalSourceUrl != sourceId ||
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
                    barrierRevision = null
                    barrierScheduledStartAt = null
                    recordDiagnostic("barrier.final_ready_timeout", "revision=$revision")
                    return@launch
                }

                // Project the command's position forward to the scheduled anchor first.
                // A late peer must join the existing track timeline, not restart at zero.
                val commandAt = parseTimestamp(
                    currentRemote.commandAt.ifBlank { currentRemote.updatedAt }
                )
                val elapsedBeforeStart = commandAt?.let {
                    (startAtMs - it).coerceAtLeast(0L)
                } ?: 0L
                val positionAtStart = RoomPlaybackMath.expectedRemotePositionMs(
                    positionMs = currentRemote.positionMs,
                    isPlaying = true,
                    elapsedSinceUpdateMs = elapsedBeforeStart,
                    durationMs = currentRemote.durationMs
                )
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
                    barrierRevision != revision ||
                    pendingAction != null
                ) return@launch
                player.play()
                // Keep this revision eligible for a delayed START broadcast. If the
                // peer becomes ready just before its server-side READY row expires,
                // its scheduled anchor must still be able to realign this local player.
                barrierScheduledStartAt = null
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
                if (isCurrent(roomId, generation)) {
                    setError(error, "Synchronized playback could not start")
                    recordDiagnostic("barrier.start_error", error.message.orEmpty())
                    barrierRevision = null
                    barrierScheduledStartAt = null
                }
            }
        }
    }

    private fun cancelStartBarrier(reason: String) {
        if (barrierRevision == null) return
        recordDiagnostic("barrier.cancelled", "revision=$barrierRevision reason=$reason")
        barrierJob?.cancel()
        barrierJob = null
        barrierStartJob?.cancel()
        barrierStartJob = null
        barrierRevision = null
        barrierScheduledStartAt = null
        mutableState.value = mutableState.value.copy(statusMessage = null)
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

        val anchorTime = if (playbackAnchorRevision == remote.revision) {
            playbackAnchorAt?.let(::parseTimestamp)
        } else {
            null
        } ?: parseTimestamp(remote.commandAt.ifBlank { remote.updatedAt })
        val elapsed = anchorTime?.let {
            (clock.estimatedServerTimeMs() - it).coerceAtLeast(0L)
        } ?: 0L
        val anchorPosition = if (playbackAnchorRevision == remote.revision) {
            playbackAnchorPositionMs ?: remote.positionMs
        } else {
            remote.positionMs
        }
        val expected = RoomPlaybackMath.expectedRemotePositionMs(
            positionMs = anchorPosition,
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
        mutableState.value = mutableState.value.copy(
            lastError = "$prefix · $message",
            statusMessage = null
        )
    }

    private fun parseTimestamp(value: String): Long? = RoomTimestampParser.parse(value)

    private fun serverTimestamp(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(clock.estimatedServerTimeMs()))

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 25_000L
        const val CLOCK_CALIBRATION_INTERVAL_MS = 300_000L
        const val TRACK_READY_TIMEOUT_MS = 20_000L
        const val BARRIER_READY_TIMEOUT_MS = 46_000L
        const val BARRIER_READY_RETRY_MS = 100L
        const val BARRIER_FINAL_READY_TIMEOUT_MS = 20_000L
        const val LOCAL_ACTION_WIN_MARGIN_MS = 200L
        const val MAX_DIAGNOSTIC_EVENTS = 120
    }
}
