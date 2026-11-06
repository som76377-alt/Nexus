package com.nexus.music.rooms.data

import com.nexus.music.rooms.RoomsApi
import com.nexus.music.rooms.domain.RoomClock
import android.os.SystemClock

/** Uses the same five-probe calibrated clock already used by the room backend client. */
internal class SupabaseRoomClock(
    private val api: RoomsApi
) : RoomClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun estimatedServerTimeMs(): Long = api.serverNowMillis()

    override fun estimatedRoundTripTimeMs(): Long = api.serverClockRoundTripMillis()

    override suspend fun calibrate() {
        api.calibrateServerClock()
    }
}
