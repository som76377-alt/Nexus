package com.nexus.music.rooms

import java.util.Locale

/** Human-friendly, case-insensitive five-character invite codes. */
object RoomInviteCode {
    const val LENGTH = 5
    private val pattern = Regex("^[A-HJ-NP-Z2-9]{5}$")

    fun normalize(value: String): String = value.trim().uppercase(Locale.US)

    fun isValid(value: String): Boolean = pattern.matches(normalize(value))
}
