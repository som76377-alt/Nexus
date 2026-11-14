package com.nexus.music.diagnostics

import com.nexus.music.catalog.ProviderOperation
import com.nexus.music.catalog.StreamErrorClass
import com.nexus.music.catalog.StreamProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderLabHealthStoreTest {
    @Test
    fun eachLabStoreIsDisposableAndIndependent() {
        val labStore = createProviderLabHealthStore()
        labStore.recordFailure(
            provider = StreamProvider.PIPED,
            errorClass = StreamErrorClass.TRANSIENT,
            atEpochMs = 1_000L,
            cooldownUntilEpochMs = 2_000L,
            operation = ProviderOperation.SEARCH
        )

        val nextLabRun = createProviderLabHealthStore()

        assertEquals(1, labStore.get(StreamProvider.PIPED, ProviderOperation.SEARCH).consecutiveFailures)
        assertEquals(0, nextLabRun.get(StreamProvider.PIPED, ProviderOperation.SEARCH).consecutiveFailures)
        assertEquals(0, nextLabRun.get(StreamProvider.PIPED, ProviderOperation.RESOLVE).consecutiveFailures)
    }
}
