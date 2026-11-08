package com.nexus.music.rooms.data

import com.nexus.music.rooms.RoomRealtimeClient
import com.nexus.music.rooms.RoomsApi
import com.nexus.music.rooms.domain.RoomActionType
import com.nexus.music.rooms.domain.RoomCommand
import com.nexus.music.rooms.domain.RoomPlaybackSnapshot
import com.nexus.music.rooms.domain.RoomPlaybackReadyResult
import com.nexus.music.rooms.domain.RoomPublishResult
import com.nexus.music.rooms.domain.RoomRealtimeSignal
import com.nexus.music.rooms.domain.RoomRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Maps the app's existing authenticated REST/RPC and Phoenix clients to the
 * player/backend-neutral room domain. It deliberately adds no second HTTP stack.
 */
internal class SupabaseRoomRepository(
    private val api: RoomsApi
) : RoomRepository {

    override suspend fun currentUserId(): String = api.identity().userId

    override suspend fun fetchPlaybackSnapshot(roomId: String): RoomPlaybackSnapshot? =
        api.loadPlayback(roomId)?.let { row ->
            RoomPlaybackSnapshot(
                roomId = roomId,
                sourceId = row.sourceId,
                canonicalSourceUrl = row.sourceUrl,
                title = row.title,
                artist = row.artist,
                isPlaying = row.isPlaying,
                positionMs = row.positionMs.coerceAtLeast(0L),
                durationMs = row.durationMs.coerceAtLeast(0L),
                revision = row.revision.coerceAtLeast(0L),
                updatedBy = row.updatedBy,
                updatedAt = row.updatedAt,
                actionId = row.actionId,
                actionType = RoomActionType.fromWireValue(row.actionType)
                    ?: RoomActionType.STATE,
                originDeviceId = row.originDeviceId,
                commandAt = row.commandAt
            )
        }

    override suspend fun publishPlayback(command: RoomCommand): RoomPublishResult {
        val result = api.publishPlayback(
            roomId = command.roomId,
            expectedRevision = command.expectedRevision,
            sourceId = command.sourceId,
            sourceUrl = command.canonicalSourceUrl,
            title = command.title,
            artist = command.artist.orEmpty(),
            isPlaying = command.isPlaying,
            positionMs = command.positionMs,
            durationMs = command.durationMs,
            actionId = command.actionId,
            actionType = command.actionType.wireValue,
            originDeviceId = command.originDeviceId
        )
        return RoomPublishResult(result.applied, result.revision)
    }

    override suspend fun markPlaybackReady(
        roomId: String,
        revision: Long,
        sourceId: String
    ): RoomPlaybackReadyResult {
        val result = api.markPlaybackReady(roomId, revision, sourceId)
        return RoomPlaybackReadyResult(
            startAt = result.startAt,
            activeMembers = result.activeMembers,
            readyMembers = result.readyMembers
        )
    }

    override fun observeRealtimeSignals(roomId: String): Flow<RoomRealtimeSignal> =
        callbackFlow {
            val client = RoomRealtimeClient(
                api = api,
                roomId = roomId,
                scope = this,
                onPlaybackSignal = { snapshot ->
                    trySend(
                        snapshot?.let { RoomRealtimeSignal.PlaybackUpdated(it) }
                            ?: RoomRealtimeSignal.PlaybackInvalidated
                    )
                },
                onChatSignal = {
                    trySend(RoomRealtimeSignal.ChatInvalidated)
                },
                onStartScheduled = { revision, sourceId, startAt ->
                    trySend(
                        RoomRealtimeSignal.StartScheduled(
                            revision = revision,
                            sourceId = sourceId,
                            startAt = startAt
                        )
                    )
                },
                onConnectionChanged = { connected ->
                    trySend(RoomRealtimeSignal.ConnectionChanged(connected))
                }
            )
            client.start()
            awaitClose { client.close() }
        }

    override suspend fun touchRoom(roomId: String): Boolean = api.touchRoom(roomId)

}
