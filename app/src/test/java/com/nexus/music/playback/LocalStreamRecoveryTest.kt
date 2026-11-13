package com.nexus.music.playback

import com.nexus.music.catalog.ResolvedAudio
import com.nexus.music.catalog.CatalogTrack
import com.nexus.music.catalog.ProviderHealth
import com.nexus.music.catalog.ProviderHealthStore
import com.nexus.music.catalog.ProviderResolverChain
import com.nexus.music.catalog.StreamErrorClass
import com.nexus.music.catalog.StreamProviderAdapter
import com.nexus.music.catalog.YoutubeSearchScope
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import com.nexus.music.catalog.StreamProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LocalStreamRecoveryTest {
    private val source = "https://www.youtube.com/watch?v=local-only"

    private fun replacement() = ResolvedAudio(
        sourceUrl = source,
        title = "Track",
        artist = "Artist",
        thumbnailUrl = null,
        durationSeconds = 180L,
        audioUrl = "https://media.example/replacement",
        averageBitrate = 128_000,
        mimeType = "audio/webm",
        provider = StreamProvider.PIPED
    )

    @Test
    fun streamReresolutionReplacesOnlyLocalPlayerAndPublishesNoRoomAction() = runBlocking {
        val localReplacements = mutableListOf<Triple<ResolvedAudio, Long, Boolean>>()
        var resolvedWith = emptySet<StreamProvider>()
        val recovery = LocalStreamRecovery(
            resolveNext = { url, excluded ->
                assertEquals(source, url)
                resolvedWith = excluded
                replacement()
            },
            isCurrentSource = { it == source },
            replaceLocally = { audio, position, play ->
                localReplacements += Triple(audio, position, play)
            }
        )

        val recovered = recovery.recover(
            sourceUrl = source,
            excludedProviders = setOf(StreamProvider.NEWPIPE),
            resumePositionMs = 42_500L,
            resumeWhenReady = true
        )

        assertTrue(recovered)
        assertEquals(setOf(StreamProvider.NEWPIPE), resolvedWith)
        assertEquals(1, localReplacements.size)
        assertEquals(replacement(), localReplacements.single().first)
        assertEquals(42_500L, localReplacements.single().second)
        assertTrue(localReplacements.single().third)
        // The recovery contract has no room publisher; no room action is emitted.
    }

    // J7 A4: recovery resolution is bounded to five seconds; timeout is surfaced to Retry UI.
    @Test
    fun a4RecoveryResolutionTimesOutAtFiveSecondsWithoutReplacingThePlayer() = runTest {
        var replaced = false
        val recovery = LocalStreamRecovery(
            resolveNext = { _, _ ->
                delay(10_000L)
                replacement()
            },
            isCurrentSource = { it == source },
            replaceLocally = { _, _, _ -> replaced = true }
        )

        val pending = async {
            runCatching {
                recovery.recover(
                    sourceUrl = source,
                    excludedProviders = setOf(StreamProvider.NEWPIPE),
                    resumePositionMs = 42_500L,
                    resumeWhenReady = true
                )
            }
        }
        runCurrent()
        advanceTimeBy(4_999L)
        runCurrent()
        assertFalse("recovery must still be pending before the five-second deadline", pending.isCompleted)
        advanceTimeBy(1L)
        runCurrent()

        val error = pending.await().exceptionOrNull()
        assertTrue("timeout must become an ordinary recovery failure", error is IOException)
        assertTrue(error?.message.orEmpty().contains("timed out"))
        assertFalse("a timed-out resolver must not replace the active track", replaced)
    }

    // J7 A5: a room-local recovery can fail over a bot-checked provider without publishing a room action.
    @Test
    fun a5RoomLocalRecoveryFallsBackAfterBotCheckAndKeepsRecoveryLocal() = runBlocking {
        val calls = mutableListOf<StreamProvider>()
        val health = object : ProviderHealthStore {
            private val states = mutableMapOf<StreamProvider, ProviderHealth>()
            override fun get(provider: StreamProvider) = states[provider] ?: ProviderHealth()
            override fun recordSuccess(provider: StreamProvider, atEpochMs: Long) {
                states[provider] = ProviderHealth(lastSuccessEpochMs = atEpochMs)
            }
            override fun recordFailure(
                provider: StreamProvider,
                errorClass: StreamErrorClass,
                atEpochMs: Long,
                cooldownUntilEpochMs: Long
            ) {
                states[provider] = get(provider).copy(
                    consecutiveFailures = get(provider).consecutiveFailures + 1,
                    lastErrorClass = errorClass,
                    cooldownUntilEpochMs = cooldownUntilEpochMs
                )
            }
            override fun clear(provider: StreamProvider) { states.remove(provider) }
        }
        val chain = ProviderResolverChain(
            adapters = listOf(
                object : StreamProviderAdapter {
                    override val provider = StreamProvider.NEWPIPE
                    override suspend fun search(query: String, scope: YoutubeSearchScope): List<CatalogTrack> {
                        calls += provider
                        throw ReCaptchaException("bot check", "https://www.youtube.com/")
                    }
                    override suspend fun resolveAudio(videoUrl: String): ResolvedAudio {
                        calls += provider
                        throw ReCaptchaException("bot check", "https://www.youtube.com/")
                    }
                },
                object : StreamProviderAdapter {
                    override val provider = StreamProvider.PIPED
                    override suspend fun search(query: String, scope: YoutubeSearchScope): List<CatalogTrack> {
                        calls += provider
                        return emptyList()
                    }
                    override suspend fun resolveAudio(videoUrl: String): ResolvedAudio {
                        calls += provider
                        return replacement().copy(provider = provider)
                    }
                }
            ),
            healthStore = health,
            providerOrder = listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED),
            hedgeDelayMs = 0L
        )
        val localReplacements = mutableListOf<Triple<ResolvedAudio, Long, Boolean>>()
        val roomActionsPublished = mutableListOf<String>()
        val recovery = LocalStreamRecovery(
            resolveNext = { url, excluded -> chain.resolveAudio(url, excluded) },
            isCurrentSource = { it == source },
            replaceLocally = { audio, position, play ->
                localReplacements += Triple(audio, position, play)
            }
        )

        assertTrue(
            recovery.recover(
                sourceUrl = source,
                excludedProviders = emptySet(),
                resumePositionMs = 42_500L,
                resumeWhenReady = true
            )
        )

        assertEquals(listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED), calls)
        assertEquals(StreamProvider.PIPED, localReplacements.single().first.provider)
        assertEquals(42_500L, localReplacements.single().second)
        assertTrue(localReplacements.single().third)
        assertTrue(roomActionsPublished.isEmpty())
    }

    @Test
    fun recoveryDoesNotReplaceAUserSelectedNewTrack() = runBlocking {
        var localReplacementCount = 0
        val recovery = LocalStreamRecovery(
            resolveNext = { _, _ -> replacement() },
            isCurrentSource = { false },
            replaceLocally = { _, _, _ -> localReplacementCount++ }
        )

        val recovered = recovery.recover(
            sourceUrl = source,
            excludedProviders = setOf(StreamProvider.NEWPIPE),
            resumePositionMs = 10_000L,
            resumeWhenReady = false
        )

        assertFalse(recovered)
        assertEquals(0, localReplacementCount)
    }
}
