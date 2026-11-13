package com.nexus.music.playback

import com.nexus.music.catalog.ResolvedAudio
import com.nexus.music.catalog.StreamProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val roomActionsPublished = mutableListOf<String>()
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
