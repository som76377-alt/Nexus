package com.nexus.music.update

import org.junit.Assert.assertEquals
import org.junit.Test

class NexusSigningIdentityTest {
    private val official = "c8b263a4b2aecac65df71e6ee9f6e8ab47a33688a46a4cf0f4248506975d6e2b"

    @Test
    fun acceptsTheExactOfficialReleaseCertificate() {
        assertEquals(NexusSigningIdentity.OFFICIAL, classifyNexusSigner(setOf(official)))
    }

    @Test
    fun unrecognizedOrMissingSigningIdentityIsUnknownNotWrongKey() {
        assertEquals(NexusSigningIdentity.UNKNOWN, classifyNexusSigner(setOf("0".repeat(64))))
        assertEquals(NexusSigningIdentity.UNKNOWN, classifyNexusSigner(emptySet()))
    }

    @Test
    fun rejectsUnexpectedAdditionalSigner() {
        assertEquals(NexusSigningIdentity.UNKNOWN, classifyNexusSigner(setOf(official, "0".repeat(64))))
    }
}
