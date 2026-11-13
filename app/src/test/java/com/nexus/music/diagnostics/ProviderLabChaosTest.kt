package com.nexus.music.diagnostics

import com.nexus.music.catalog.CatalogTrack
import com.nexus.music.catalog.ProviderCapabilityException
import com.nexus.music.catalog.StreamErrorClass
import com.nexus.music.catalog.StreamErrorClassifier
import com.nexus.music.catalog.StreamProvider
import com.nexus.music.catalog.StreamProviderAdapter
import com.nexus.music.catalog.YoutubeSearchScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.IOException

class ProviderLabChaosTest {
    @Test
    fun debugFactoryCanForceEveryDocumentedProviderFailureClass() = runBlocking {
        val chaos = ProviderLabChaosFactory.create()
        assertNotNull(chaos)
        val expected = mapOf(
            ProviderLabFailure.BOT_CHECK to StreamErrorClass.RATE_LIMITED,
            ProviderLabFailure.RATE_LIMITED to StreamErrorClass.RATE_LIMITED,
            ProviderLabFailure.EXTRACTOR_BROKEN to StreamErrorClass.UNKNOWN,
            ProviderLabFailure.NETWORK to StreamErrorClass.NETWORK,
            ProviderLabFailure.CONTENT_UNAVAILABLE to StreamErrorClass.CONTENT_UNAVAILABLE,
            ProviderLabFailure.STREAM_FORBIDDEN to StreamErrorClass.ACCESS_BLOCKED
        )
        expected.forEach { (failure, errorClass) ->
            val wrapped = chaos!!.wrap(FakeAdapter, StreamProvider.NEWPIPE, failure)
            val error = runCatching { wrapped.search("sample", YoutubeSearchScope.EVERYTHING) }.exceptionOrNull()
            assertNotNull("Expected forced failure for $failure", error)
            assertEquals(failure.name, errorClass, StreamErrorClassifier.classify(error!!))
        }
    }

    @Test
    fun noneLeavesProviderAdapterUnchanged() {
        val chaos = ProviderLabChaosFactory.create()!!
        assertEquals(
            FakeAdapter,
            chaos.wrap(FakeAdapter, StreamProvider.NEWPIPE, ProviderLabFailure.NONE)
        )
    }

    private object FakeAdapter : StreamProviderAdapter {
        override val provider = StreamProvider.NEWPIPE
        override suspend fun search(query: String, scope: YoutubeSearchScope): List<CatalogTrack> = emptyList()
        override suspend fun resolveAudio(videoUrl: String): com.nexus.music.catalog.ResolvedAudio {
            throw IOException("not used")
        }
    }
}
