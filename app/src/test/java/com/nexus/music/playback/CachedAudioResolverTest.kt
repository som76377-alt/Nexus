package com.nexus.music.playback

import com.nexus.music.catalog.ResolvedAudio
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CachedAudioResolverTest {
    @Test
    fun persistentCacheLookupsCompleteBeforeProviderNetworkResolution() = runBlocking {
        val events = mutableListOf<String>()
        val cache = object : AudioCacheLookup {
            override suspend fun fullyCached(sourceUrl: String): ResolvedAudio? {
                events += "persistent_full_lookup"
                return null
            }

            override suspend fun freshUrl(sourceUrl: String, nowEpochMs: Long): ResolvedAudio? {
                events += "persistent_fresh_url_lookup"
                return null
            }

            override suspend fun remember(audio: ResolvedAudio, nowEpochMs: Long) {
                events += "persistent_metadata_save"
            }
        }
        val resolver = CachedAudioResolver(cache, {
            events += "provider_network"
            audio()
        })

        resolver.resolve("https://www.youtube.com/watch?v=abcdefghi")

        assertEquals(
            listOf(
                "persistent_full_lookup",
                "persistent_fresh_url_lookup",
                "provider_network",
                "persistent_metadata_save"
            ),
            events
        )
    }

    @Test
    fun fullyCachedPersistentAudioBypassesProviderNetwork() = runBlocking {
        val cached = audio()
        var providerCalls = 0
        val cache = object : AudioCacheLookup {
            override suspend fun fullyCached(sourceUrl: String): ResolvedAudio = cached
            override suspend fun freshUrl(sourceUrl: String, nowEpochMs: Long): ResolvedAudio? = null
            override suspend fun remember(audio: ResolvedAudio, nowEpochMs: Long) = Unit
        }

        val result = CachedAudioResolver(cache, {
            providerCalls++
            error("A full persistent cache hit must bypass the network")
        }).resolve(cached.sourceUrl)

        assertSame(cached, result)
        assertEquals(0, providerCalls)
    }

    private fun audio() = ResolvedAudio(
        sourceUrl = "https://www.youtube.com/watch?v=abcdefghi",
        title = "Cached track",
        artist = null,
        thumbnailUrl = null,
        durationSeconds = 60L,
        audioUrl = "https://audio.example/cached",
        averageBitrate = 128_000,
        mimeType = "audio/webm"
    )
}
