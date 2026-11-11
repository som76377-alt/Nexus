package com.nexus.music.rooms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomInviteCodeTest {
    @Test
    fun acceptsFiveCharacterCodesWithoutAmbiguousCharacters() {
        assertTrue(RoomInviteCode.isValid("AB7KQ"))
        assertTrue(RoomInviteCode.isValid("ab7kq"))
    }

    @Test
    fun rejectsAmbiguousCharacters() {
        assertFalse(RoomInviteCode.isValid("AB0KQ"))
        assertFalse(RoomInviteCode.isValid("AB1KQ"))
        assertFalse(RoomInviteCode.isValid("ABIKQ"))
        assertFalse(RoomInviteCode.isValid("ABOKQ"))
    }

    @Test
    fun acceptsLegacySixteenCharacterHexCodes() {
        assertTrue(RoomInviteCode.isValid("0123456789ABCDEF"))
        assertTrue(RoomInviteCode.isValid("0123456789abcdef"))
    }

    @Test
    fun rejectsWrongLengthsAndPunctuation() {
        assertFalse(RoomInviteCode.isValid("AB7K"))
        assertFalse(RoomInviteCode.isValid("AB7KQR"))
        assertFalse(RoomInviteCode.isValid("AB-7K"))
        assertFalse(RoomInviteCode.isValid("0123456789ABCDEG"))
    }
}
