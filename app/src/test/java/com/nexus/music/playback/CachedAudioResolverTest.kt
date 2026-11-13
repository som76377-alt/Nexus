package com.nexus.music.playback

import com.nexus.music.catalog.ResolvedAudio
import com.nexus.music.catalog.StreamProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedAudioResolverTest {
    private val source = "https://www.youtube.com/watch?v=abcDEF_1234"
    private fun audio(url: String) = ResolvedAudio(source, "Track", "Artist", null, 180L, url, 128_000, "audio/webm", provider = StreamProvider.NEWPIPE)

    private class FakeCache(var full: ResolvedAudio? = null, var fresh: ResolvedAudio? = null) : AudioCacheLookup {
        val calls = mutableListOf<String>()
        var remembered: ResolvedAudio? = null
        var evicted: Pair<String, String>? = null
        override suspend fun evictFailed(sourceUrl: String, audioUrl: String) { evicted = sourceUrl to audioUrl }
        override suspend fun fullyCached(sourceUrl: String): ResolvedAudio? { calls += "full"; return full }
        override suspend fun freshUrl(sourceUrl: String, nowEpochMs: Long): ResolvedAudio? { calls += "fresh"; return fresh }
        override suspend fun remember(audio: ResolvedAudio, nowEpochMs: Long) { calls += "remember"; remembered = audio }
    }

    @Test
    fun fullyCachedAudioWinsBeforeAnyProviderCall() = runBlocking {
        val cached = audio("https://expired.example/audio")
        val cache = FakeCache(full = cached)
        var resolverCalls = 0
        val resolver = CachedAudioResolver(cache, { resolverCalls++; error("provider must not be called") }, nowEpochMs = { 1_000L })
        assertSame(cached, resolver.resolve(source))
        assertEquals(0, resolverCalls)
        assertEquals(listOf("full"), cache.calls)
    }

    @Test
    fun a1OfflineAcceptanceUsesCompleteCacheWithoutNetworkOrProviderResolution() = runBlocking {
        val cached = audio("https://expired.example/audio")
        val cache = FakeCache(full = cached, fresh = null)
        var providerCalls = 0
        val resolver = CachedAudioResolver(cache, { providerCalls++; error("offline acceptance must not reach network") }, nowEpochMs = { Long.MAX_VALUE })
        assertSame(cached, resolver.resolve(source))
        assertEquals(0, providerCalls)
        assertEquals(listOf("full"), cache.calls)
    }

    @Test
    fun failedStreamCacheCanBeEvictedBeforeLocalProviderRetry() = runBlocking {
        val cache = FakeCache()
        val failedUrl = "https://rr.googlevideo.com/audio?itag=251&expire=1800000000"
        cache.evictFailed(source, failedUrl)
        assertEquals(source to failedUrl, cache.evicted)
    }

    @Test
    fun freshUrlCacheIsUsedBeforeProviderChain() = runBlocking {
        val cached = audio("https://fresh.example/audio")
        val cache = FakeCache(fresh = cached)
        val resolver = CachedAudioResolver(cache, { error("fresh URL should avoid provider resolution") }, nowEpochMs = { 1_000L })
        assertSame(cached, resolver.resolve(source))
        assertEquals(listOf("full", "fresh"), cache.calls)
    }

    @Test
    fun cacheMissResolvesAndRemembersOnlyTheAcceptedProviderResult() = runBlocking {
        val resolved = audio("https://resolved.example/audio")
        val cache = FakeCache()
        var resolverCalls = 0
        val resolver = CachedAudioResolver(cache, { resolverCalls++; resolved }, nowEpochMs = { 1_000L })
        assertSame(resolved, resolver.resolve(source))
        assertEquals(1, resolverCalls)
        assertSame(resolved, cache.remembered)
        assertEquals(listOf("full", "fresh", "remember"), cache.calls)
    }

    @Test
    fun staleUrlIsNeverReturnedWhenProviderResolutionFails() = runBlocking {
        val cache = FakeCache()
        var resolverCalls = 0
        val resolver = CachedAudioResolver(cache, { resolverCalls++; throw IllegalStateException("all providers failed") }, nowEpochMs = { 1_000L })
        val error = assertThrows(IllegalStateException::class.java) { runBlocking { resolver.resolve(source) } }
        assertEquals("all providers failed", error.message)
        assertEquals(1, resolverCalls)
        assertTrue(cache.remembered == null)
        assertEquals(listOf("full", "fresh"), cache.calls)
    }
}
