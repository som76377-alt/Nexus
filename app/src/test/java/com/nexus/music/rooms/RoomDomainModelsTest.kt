package com.nexus.music.rooms

import com.nexus.music.rooms.domain.RoomActionType
import com.nexus.music.rooms.domain.RoomCommand
import com.nexus.music.rooms.domain.RoomDiagnosticsDeviceInfo
import com.nexus.music.rooms.domain.RoomDiagnosticsFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // SPEC: S1–S8 diagnostic observability only; these tests do not claim device verification.
    @Test
    fun diagnosticsFormatterIncludesDeviceClockPhaseAndActionTimeline() {
        val text = RoomDiagnosticsFormatter.format(
            device = RoomDiagnosticsDeviceInfo(
                appVersion = "0.1.0-debug",
                deviceModel = "Example Phone",
                apiLevel = 35,
                wakeMode = "NETWORK"
            ),
            clockOffsetMs = 42L,
            roundTripMs = 19L,
            phase = "ROOM_PLAYING",
            events = listOf(
                "action_id=action-1 action_type=PLAY intent_time_elapsed_ms=100 " +
                    "publish_ack_elapsed_ms=120 received_elapsed_ms=130 " +
                    "applied_elapsed_ms=140 resulting_drift_ms=12"
            )
        )

        assertTrue(text.contains("app_version=0.1.0-debug"))
        assertTrue(text.contains("device_model=Example Phone"))
        assertTrue(text.contains("api_level=35"))
        assertTrue(text.contains("wake_mode=NETWORK"))
        assertTrue(text.contains("clock_offset_ms=42"))
        assertTrue(text.contains("clock_rtt_ms=19"))
        assertTrue(text.contains("phase=ROOM_PLAYING"))
        assertTrue(text.contains("action_id=action-1"))
        assertTrue(text.contains("publish_ack_elapsed_ms=120"))
        assertTrue(text.contains("received_elapsed_ms=130"))
        assertTrue(text.contains("applied_elapsed_ms=140"))
        assertTrue(text.contains("resulting_drift_ms=12"))
    }

    // SPEC: S1–S8 diagnostic observability only; retain a bounded export timeline.
    @Test
    fun diagnosticsFormatterExportsOnlyTheNewestThreeHundredEntries() {
        val events = (0..350).map { "event=$it" }
        val text = RoomDiagnosticsFormatter.format(
            device = RoomDiagnosticsDeviceInfo(),
            clockOffsetMs = null,
            roundTripMs = null,
            phase = "LOCAL",
            events = events
        )

        assertFalse(text.contains("event=0\n"))
        assertTrue(text.contains("event=350\n"))
        assertEquals(300, text.lineSequence().count { it.startsWith("event=") })
    }
}
