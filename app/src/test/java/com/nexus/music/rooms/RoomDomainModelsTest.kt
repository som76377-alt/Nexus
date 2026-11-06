package com.nexus.music.rooms

import com.nexus.music.rooms.domain.RoomActionType
import com.nexus.music.rooms.domain.RoomCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RoomDomainModelsTest {
    @Test
    fun actionTypesMatchTheDatabaseWireContract() {
        assertEquals(RoomActionType.TRACK, RoomActionType.fromWireValue("TRACK"))
        assertEquals(RoomActionType.PLAY, RoomActionType.fromWireValue("PLAY"))
        assertEquals(RoomActionType.PAUSE, RoomActionType.fromWireValue("PAUSE"))
        assertEquals(RoomActionType.SEEK, RoomActionType.fromWireValue("SEEK"))
        assertEquals(RoomActionType.RECOVERY, RoomActionType.fromWireValue("RECOVERY"))
        assertEquals(RoomActionType.STATE, RoomActionType.fromWireValue("STATE"))
        assertEquals(null, RoomActionType.fromWireValue("LAMPORT"))
    }

    @Test
    fun commandRejectsNegativeRevision() {
        assertThrows(IllegalArgumentException::class.java) {
            RoomCommand(
                roomId = "room",
                actionId = "action",
                expectedRevision = -1L,
                originDeviceId = "device",
                actionType = RoomActionType.PLAY,
                sourceId = "video",
                canonicalSourceUrl = "https://www.youtube.com/watch?v=video",
                title = "Track",
                artist = null,
                isPlaying = true,
                positionMs = 0L,
                durationMs = 120_000L
            )
        }
    }

    @Test
    fun commandAcceptsRevisionZeroAndZeroPosition() {
        val command = RoomCommand(
            roomId = "room",
            actionId = "action",
            expectedRevision = 0L,
            originDeviceId = "device",
            actionType = RoomActionType.TRACK,
            sourceId = "video",
            canonicalSourceUrl = "https://www.youtube.com/watch?v=video",
            title = "Track",
            artist = null,
            isPlaying = false,
            positionMs = 0L,
            durationMs = 120_000L
        )

        assertEquals(0L, command.expectedRevision)
        assertEquals(0L, command.positionMs)
    }
