package com.nexus.music.rooms

import com.nexus.music.rooms.domain.RoomActionType
import com.nexus.music.rooms.domain.RoomClock
import com.nexus.music.rooms.domain.RoomCommand
import com.nexus.music.rooms.domain.RoomConnectionState
import com.nexus.music.rooms.domain.RoomController
import com.nexus.music.rooms.domain.RoomControllerState
import com.nexus.music.rooms.domain.RoomPlayerIntent
import com.nexus.music.rooms.domain.RoomPlayerPort
import com.nexus.music.rooms.domain.RoomPlaybackSnapshot
import com.nexus.music.rooms.domain.RoomPublishResult
import com.nexus.music.rooms.domain.RoomRealtimeSignal
import com.nexus.music.rooms.domain.RoomRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private val publishMutex = Mutex()
    private val applyMutex = Mutex()
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
    private var lastPublishedGeneration = -1L
    private var userId: String? = null
    private var lastRemoteRevision = 0L
    private var lastAppliedRevision = 0L
    private var lastRemotePlayback: RoomPlaybackSnapshot? = null
    private var pendingAction: PendingLocalAction? = null
    private var lastPublishedSource = ""
    private var applyingRemote = false
    private var expectedRemoteStartSource: String? = null
    private var lastObservedIsPlaying = player.snapshot().isPlaying
    private var lastLocalIntentElapsedMs = 0L
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
        controllerScope.launch {
            player.playback.collect { local ->
                // If remote PLAY had to wait for buffering, consume that expected transition
                // rather than publishing it back as a fresh local action.
                val started = local.isPlaying && !lastObservedIsPlaying
                lastObservedIsPlaying = local.isPlaying
                if (!started || expectedRemoteStartSource == local.sourceId) {
                    if (expectedRemoteStartSource == local.sourceId && started) {
                        expectedRemoteStartSource = null
                    }
                    return@collect
                }
                val activeId = activeRoomId ?: return@collect
                if (pendingAction == null &&
                    local.sourceId.isNotBlank() &&
                    local.sourceId == lastPublishedSource &&
                    clock.elapsedRealtimeMs() - lastLocalIntentElapsedMs > 750L
                ) {
                    captureLocalIntent(RoomPlayerIntent.Play)
                    // Keep this observer service-scoped; it does not poll position.
                    requestReconciliation(activeId, sessionGeneration)
                }
            }
        }
    }

    override suspend fun attach(roomId: String) {
        require(roomId.isNotBlank()) { "Room ID must not be blank." }
        stopRoomSession()
        val generation = ++sessionGeneration
        activeRoomId = roomId
        mutableState.value = RoomControllerState(
            roomId = roomId,
            connection = RoomConnectionState.CONNECTING
        )
        pendingAction = null
        lastRemoteRevision = 0L
        lastAppliedRevision = 0L
        lastRemotePlayback = null
        lastPublishedSource = ""
        lastPublishedGeneration = -1L
        expectedRemoteStartSource = null
        lastObservedIsPlaying = player.snapshot().isPlaying
        lastLocalIntentElapsedMs = 0L
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
        lastPublishedSource = ""
        lastPublishedGeneration = -1L
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

    private fun captureLocalIntent(intent: RoomPlayerIntent) {
        val roomId = activeRoomId ?: return
        val elapsed = clock.elapsedRealtimeMs()
        lastLocalIntentElapsedMs = elapsed
        expectedRemoteStartSource = null
        val current = player.snapshot()
        val previous = pendingAction
        val preserveTrack = previous?.actionType == RoomActionType.TRACK &&
            elapsed - previous.elapsedMs in 0L..750L

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
        } ?: return

        pendingAction = next
        val scope = roomScope ?: return
        if (publishJob?.isActive == true) return
        publishJob = scope.launch {
            delay(100L)
            publishPendingAction(roomId, sessionGeneration)
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
                isPlaying = local.isPlaying,
                positionMs = local.positionMs.coerceAtLeast(0L),
                durationMs = local.durationMs.coerceAtLeast(0L)
            )

            val result = repository.publishPlayback(command)
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
                val retry = repository.publishPlayback(rebased)
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
        lastPublishedSource = command.canonicalSourceUrl
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
        applyMutex.withLock {
            if (!isCurrent(roomId, generation)) return
            val remote = repository.fetchPlaybackSnapshot(roomId) ?: return
            if (!isCurrent(roomId, generation) || remote.revision < lastRemoteRevision) return
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
                requestPublishAfterApplyLock(roomId, generation)
                return
            }

            val sameDeviceState = remote.updatedBy == userId &&
                local.sourceId == remote.canonicalSourceUrl
            if (sameDeviceState && pending == null) {
                lastAppliedRevision = maxOf(lastAppliedRevision, remote.revision)
                lastPublishedSource = remote.canonicalSourceUrl
                retryPolicy.recordSuccess(remote.revision)
                return
            }

            val changed = remote.revision > lastAppliedRevision &&
                (remote.actionType != RoomActionType.STATE ||
                    local.sourceId != remote.canonicalSourceUrl ||
                    local.isPlaying != remote.isPlaying)
            val shouldRetry = retryPolicy.shouldRetry(
                remote.revision,
                clock.elapsedRealtimeMs()
            )
            if (changed || shouldRetry) {
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
        }
    }

    /** Called from a lock-held path; schedule publication after releasing applyMutex. */
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
        val localGeneration = localActionGeneration
        val startingSignal = signalSequence
        val before = player.snapshot()
        applyingRemote = true
        try {
            if (before.sourceId != remote.canonicalSourceUrl) {
                val audio = mediaResolver.resolve(
                    remote.sourceId,
                    remote.canonicalSourceUrl
                )
                if (!isCurrent(roomId, generation) ||
                    localActionGeneration != localGeneration ||
                    signalSequence != startingSignal
                ) {
                    requestReconciliation(roomId, generation)
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
                requestReconciliation(roomId, generation)
                return
            }

            val newest = repository.fetchPlaybackSnapshot(roomId)
            if (!isCurrent(roomId, generation)) return
            if (signalSequence != startingSignal ||
                (newest != null && newest.revision > remote.revision)
            ) {
                if (newest != null) {
                    lastRemotePlayback = newest
                    lastRemoteRevision = maxOf(lastRemoteRevision, newest.revision)
                }
                requestReconciliation(roomId, generation)
                return
            }

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
            val current = player.snapshot()
            if (RoomPlaybackMath.needsSeek(current.positionMs, expected) ||
                (!remote.isPlaying && current.isPlaying && current.positionMs != expected)
            ) {
                player.seekTo(expected)
            }
            player.resetPlaybackSpeed()
            driftController.reset()
            driftSeekGate.reset()
            expectedRemoteStartSource = if (remote.isPlaying) {
                remote.canonicalSourceUrl
            } else {
                null
            }
            if (remote.isPlaying) player.play() else player.pause()
            lastAppliedRevision = maxOf(lastAppliedRevision, remote.revision)
            lastPublishedSource = remote.canonicalSourceUrl
            mutableState.value = mutableState.value.copy(
                roomId = roomId,
                connection = RoomConnectionState.CONNECTED,
                playback = remote,
                lastError = null
            )
        } finally {
            applyingRemote = false
            if (isCurrent(roomId, generation) && pendingAction != null) {
                requestPublishAfterApplyLock(roomId, generation)
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

    private fun parseTimestamp(value: String): Long? {
        if (value.isBlank()) return null
        val fractionPattern = Regex("(\\.\\d{3})\\d+(?=Z|[+-]\\d{2}:?\\d{2}$)")
        val normalized = fractionPattern.replace(value) { match -> match.groupValues[1] }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(normalized)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 25_000L
        const val CLOCK_CALIBRATION_INTERVAL_MS = 300_000L
        const val TRACK_READY_TIMEOUT_MS = 20_000L
        const val LOCAL_ACTION_WIN_MARGIN_MS = 200L
    }
}
