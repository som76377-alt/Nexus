package com.nexus.music.update

import org.junit.Assert.assertEquals
import org.junit.Test

class NexusSigningIdentityTest {
    private val official = "c8b263a4b2aecac65df71e6ee9f6e8ab47a33688a46a4cf0f4248506975d6e2b"
    private val different = "0".repeat(64)

    @Test
    fun officialSignerIsClassifiedAsOfficial() {
        assertEquals(NexusSigningIdentity.OFFICIAL, classifyNexusSigner(setOf(official)))
    }

    @Test
    fun readableNonOfficialSignerIsDifferentAndHasRequiredMessage() {
        assertEquals(NexusSigningIdentity.DIFFERENT, classifyNexusSigner(setOf(different)))
        assertEquals(
            "This install is signed with a different key. Uninstall it, then install the official build.",
            DIFFERENT_NEXUS_SIGNER_MESSAGE
        )
    }

    @Test
    fun officialAndDifferentSignersTogetherAreDifferent() {
        assertEquals(
            NexusSigningIdentity.DIFFERENT,
            classifyNexusSigner(setOf(official, different))
        )
    }

    @Test
    fun missingOrEmptySignerIsUnknown() {
        assertEquals(NexusSigningIdentity.UNKNOWN, classifyNexusSigner(emptySet()))
    }
}
