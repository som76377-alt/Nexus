package com.nexus.music.rooms

import java.util.Locale

/** New invites are five human-friendly characters; legacy hex codes remain joinable. */
object RoomInviteCode {
    const val LENGTH = 5
    const val MAX_LENGTH = 16
    private val shortPattern = Regex("^[A-HJ-NP-Z2-9]{5}$")
    private val legacyPattern = Regex("^[0-9A-F]{16}$")

    fun normalize(value: String): String = value.trim().uppercase(Locale.US)

    fun isValid(value: String): Boolean {
        val normalized = normalize(value)
        return shortPattern.matches(normalized) || legacyPattern.matches(normalized)
    }
}
