package com.nexus.music.catalog

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class JsonApiStreamProviderAdapterTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun pipedResolveSelectsDirectAudioAndPreservesMetadata() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"title":"Track","uploader":"Artist","thumbnailUrl":"https://img.example/thumb.jpg","duration":123,"audioStreams":[{"url":"https://media.example/audio.m4a","mimeType":"audio/mp4","bitrate":128000}]}"""
            )
        )

        val result = PipedStreamProviderAdapter(listOf(server.url("/").toString()))
            .resolveAudio("https://www.youtube.com/watch?v=abcdefghijk")

        assertEquals("Track", result.title)
        assertEquals("Artist", result.artist)
        assertEquals("https://media.example/audio.m4a", result.audioUrl)
        assertEquals(128000, result.averageBitrate)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", result.sourceUrl)
        assertEquals("/streams/abcdefghijk", server.takeRequest().path)
    }

    @Test
    fun invidiousSearchMapsVideoResults() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"type":"video","videoId":"abcdefghijk","title":"Track","author":"Artist","lengthSeconds":90,"viewCount":42,"videoThumbnails":[{"url":"https://img.example/thumb.jpg"}]}]"""
            )
        )

        val results = InvidiousStreamProviderAdapter(listOf(server.url("/").toString()))
            .search("  ambient music  ", YoutubeSearchScope.EVERYTHING)

        assertEquals(1, results.size)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", results.single().url)
        assertEquals("Artist", results.single().artist)
        assertEquals("/api/v1/search?q=ambient%20music", server.takeRequest().path)
    }

    @Test
    fun invidiousResolveIgnoresVideoFormatsAndSelectsAudio() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"title":"Track","author":"Artist","lengthSeconds":80,"adaptiveFormats":[{"type":"video/mp4; codecs=avc1","url":"https://media.example/video.mp4","bitrate":900000},{"type":"audio/webm; codecs=opus","url":"https://media.example/audio.webm","bitrate":160000}]}"""
            )
        )

        val result = InvidiousStreamProviderAdapter(listOf(server.url("/").toString()))
            .resolveAudio("https://youtu.be/abcdefghijk")

        assertEquals("https://media.example/audio.webm", result.audioUrl)
        assertEquals("audio/webm; codecs=opus", result.mimeType)
        assertEquals("/api/v1/videos/abcdefghijk", server.takeRequest().path)
    }

    @Test
    fun pipedRetriesConfiguredInstanceAfterHttpFailure() = runBlocking {
        val second = MockWebServer()
        second.start()
        try {
            server.enqueue(MockResponse().setResponseCode(503))
            second.enqueue(MockResponse().setBody("""{"title":"Recovered","audioStreams":[{"url":"https://media.example/a.mp3","mimeType":"audio/mpeg","bitrate":96000}]}"""))

            val result = PipedStreamProviderAdapter(
                listOf(server.url("/").toString(), second.url("/").toString())
            ).resolveAudio("https://www.youtube.com/watch?v=abcdefghijk")

            assertEquals("Recovered", result.title)
            server.takeRequest()
            assertEquals("/streams/abcdefghijk", second.takeRequest().path)
        } finally {
            second.shutdown()
        }
    }


    @Test
    fun pipedAdapterAcceptsDiscoveredEndpointsAfterStartingEmpty() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"title":"Discovered","audioStreams":[{"url":"https://media.example/a.mp3","mimeType":"audio/mpeg","bitrate":96000}]}"""
            )
        )
        val adapter = PipedStreamProviderAdapter(emptyList())
        adapter.updateEndpoints(listOf(server.url("/").toString()))

        val result = adapter.resolveAudio("https://www.youtube.com/watch?v=abcdefghijk")

        assertEquals("Discovered", result.title)
        assertEquals("/streams/abcdefghijk", server.takeRequest().path)
    }

    @Test
    fun cancellingSearchCancelsTheInFlightHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val adapter = PipedStreamProviderAdapter(listOf(server.url("/").toString()))
        val request = async(Dispatchers.IO) {
            adapter.search("ambient", YoutubeSearchScope.EVERYTHING)
        }

        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        withTimeout(1_000L) {
            request.cancelAndJoin()
        }
        assertEquals(true, request.isCancelled)
    }

    @Test
    fun rejectsNonYoutubeSourceBeforeNetworkRequest() {
        val adapter = PipedStreamProviderAdapter(listOf(server.url("/").toString()))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { adapter.resolveAudio("https://example.com/watch?v=abcdefghijk") }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun rejectsHttpProviderEndpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            PipedStreamProviderAdapter(listOf("http://piped.example"))
        }
    }
}
