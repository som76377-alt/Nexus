package com.nexus.music.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractorFailoverPolicyTest {
    @Test
    fun primaryIsAvailableInitially() {
        var now = 1_000L
        val policy = ExtractorFailoverPolicy(cooldownMillis = 500L, clock = { now })

        assertTrue(policy.shouldTryPrimary())
    }

    @Test
    fun primaryFailureOpensCircuitUntilCooldownExpires() {
        var now = 1_000L
        val policy = ExtractorFailoverPolicy(cooldownMillis = 500L, clock = { now })

        policy.primaryFailed()
        assertFalse(policy.shouldTryPrimary())

        now = 1_499L
        assertFalse(policy.shouldTryPrimary())

        now = 1_500L
        assertTrue(policy.shouldTryPrimary())
    }

    @Test
    fun primarySuccessClosesCircuitImmediately() {
        var now = 1_000L
        val policy = ExtractorFailoverPolicy(cooldownMillis = 5_000L, clock = { now })

        policy.primaryFailed()
        assertFalse(policy.shouldTryPrimary())

        policy.primarySucceeded()
        assertTrue(policy.shouldTryPrimary())
    }
}
