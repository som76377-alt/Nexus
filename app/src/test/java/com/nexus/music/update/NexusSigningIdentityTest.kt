package com.nexus.music.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusSigningIdentityTest {
    private val official = "c8b263a4b2aecac65df71e6ee9f6e8ab47a33688a46a4cf0f4248506975d6e2b"

    @Test
    fun acceptsTheExactOfficialReleaseCertificate() {
        assertTrue(isOfficialNexusSigner(setOf(official)))
    }

    @Test
    fun rejectsDebugUnknownOrMissingSigners() {
        assertFalse(isOfficialNexusSigner(setOf("0".repeat(64))))
        assertFalse(isOfficialNexusSigner(emptySet()))
    }

    @Test
    fun rejectsUnexpectedAdditionalSigner() {
        assertFalse(isOfficialNexusSigner(setOf(official, "0".repeat(64))))
    }
}
