package com.nexus.music.rooms

import android.content.Context

/**
 * Stores only the opaque room UUID so a paused room can be reattached after Android stops
 * PlaybackService or the process. Authenticated membership and room expiry remain enforced
 * by Supabase; this value is not a credential.
 */
internal object ActiveRoomSessionStore {
    private const val PREFERENCES = "nexus_active_room_session"
    private const val ROOM_ID = "room_id"

    fun save(context: Context, roomId: String) {
        if (roomId.isBlank()) return
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ROOM_ID, roomId)
            .apply()
    }

    fun read(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ROOM_ID, null)
            ?.takeIf(String::isNotBlank)

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(ROOM_ID)
            .apply()
    }
}
