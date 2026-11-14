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
import java.io.IOException
import java.net.SocketTimeoutException
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
    fun pipedSearchPassesMusicSongsFilter() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))

        PipedStreamProviderAdapter(listOf(server.url("/").toString()))
            .search("ambient music", YoutubeSearchScope.MUSIC_SONGS)

        assertEquals("/search?q=ambient%20music&filter=music_songs", server.takeRequest().path)
    }

    @Test
    fun pipedSearchPassesVideoFilter() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))

        PipedStreamProviderAdapter(listOf(server.url("/").toString()))
            .search("ambient music", YoutubeSearchScope.VIDEOS)

        assertEquals("/search?q=ambient%20music&filter=videos", server.takeRequest().path)
    }

    @Test
    fun invidiousSearchPassesVideoTypeFilter() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))

        InvidiousStreamProviderAdapter(listOf(server.url("/").toString()))
            .search("ambient music", YoutubeSearchScope.VIDEOS)

        assertEquals("/api/v1/search?q=ambient%20music&type=video", server.takeRequest().path)
    }

    @Test
    fun invidiousRejectsUnsupportedMusicSongsScopeWithoutNetworkRequest() {
        val adapter = InvidiousStreamProviderAdapter(listOf(server.url("/").toString()))

        assertThrows(ProviderCapabilityException::class.java) {
            runBlocking { adapter.search("ambient music", YoutubeSearchScope.MUSIC_SONGS) }
        }
        assertEquals(0, server.requestCount)
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
    fun rejectsOversizedProviderApiResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(8_388_609)))

        val error = assertThrows(IOException::class.java) {
            runBlocking {
                PipedStreamProviderAdapter(listOf(server.url("/").toString()))
                    .resolveAudio("https://www.youtube.com/watch?v=abcdefghijk")
            }
        }

        assertEquals("piped API response exceeded the size limit.", error.message)
        assertEquals("/streams/abcdefghijk", server.takeRequest().path)
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
    fun parsesRetryAfterAsSecondsOrHttpDate() {
        assertEquals(5_000L, JsonApiStreamProviderAdapter.parseRetryAfterMillis("5", 1_000L))
        assertEquals(
            10_000L,
            JsonApiStreamProviderAdapter.parseRetryAfterMillis("Thu, 01 Jan 1970 00:00:10 GMT", 0L)
        )
        assertEquals(
            0L,
            JsonApiStreamProviderAdapter.parseRetryAfterMillis("Thu, 01 Jan 1970 00:00:01 GMT", 2_000L)
        )
        assertEquals(null, JsonApiStreamProviderAdapter.parseRetryAfterMillis("not-a-date", 0L))
    }


    @Test
    fun searchClient4xxStatusesCooldownOnlyTheFailingInstance() = runBlocking {
        listOf(400, 401, 403, 404, 405, 410).forEach { status ->
            val first = MockWebServer()
            val second = MockWebServer()
            first.start()
            second.start()
            try {
                first.enqueue(MockResponse().setResponseCode(status))
                second.enqueue(MockResponse().setBody("[]"))
                second.enqueue(MockResponse().setBody("[]"))
                val adapter = PipedStreamProviderAdapter(
                    listOf(first.url("/").toString(), second.url("/").toString()),
                    nowEpochMs = { 1_000L }
                )

                adapter.search("ambient", YoutubeSearchScope.EVERYTHING)
                adapter.search("ambient", YoutubeSearchScope.EVERYTHING)

                assertEquals("status=$status first instance", 1, first.requestCount)
                assertEquals("status=$status healthy instance", 2, second.requestCount)
            } finally {
                first.shutdown()
                second.shutdown()
            }
        }
    }

    @Test
    fun resolveClient4xxDoesNotReuseSearchInstanceCooldown() = runBlocking {
        val second = MockWebServer()
        second.start()
        try {
            repeat(3) {
                server.enqueue(MockResponse().setResponseCode(403))
                second.enqueue(
                    MockResponse().setBody(
                        """{"title":"Recovered","audioStreams":[{"url":"https://media.example/a.mp3","mimeType":"audio/mpeg","bitrate":96000}]}"""
                    )
                )
            }
            val adapter = PipedStreamProviderAdapter(
                listOf(server.url("/").toString(), second.url("/").toString()),
                nowEpochMs = { 1_000L }
            )

            repeat(3) {
                assertEquals("Recovered", adapter.resolveAudio("https://youtube.com/watch?v=abcdefghijk").title)
            }

            assertEquals(2, server.requestCount)
            assertEquals(3, second.requestCount)
        } finally {
            second.shutdown()
        }
    }



    @Test
    fun search429HonorsRetryAfterBeforeRetryingTheInstance() = runBlocking {
        var now = 1_000L
        val second = MockWebServer()
        second.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
            server.enqueue(MockResponse().setBody("[]"))
            repeat(3) { second.enqueue(MockResponse().setBody("[]")) }
            val adapter = PipedStreamProviderAdapter(
                listOf(server.url("/").toString(), second.url("/").toString()),
                nowEpochMs = { now }
            )

            adapter.search("ambient", YoutubeSearchScope.EVERYTHING)
            now = 60_000L
            adapter.search("ambient", YoutubeSearchScope.EVERYTHING)
            assertEquals(1, server.requestCount)
            now = 61_000L
            adapter.search("ambient", YoutubeSearchScope.EVERYTHING)

            assertEquals(2, server.requestCount)
            assertEquals(2, second.requestCount)
        } finally {
            second.shutdown()
        }
    }

    @Test
    fun healthyInstancesRotateAcrossCallsWithoutChangingProviderOrder() = runBlocking {
        val second = MockWebServer()
        second.start()
        try {
            repeat(2) {
                server.enqueue(MockResponse().setBody("[]"))
                second.enqueue(MockResponse().setBody("[]"))
            }
            val adapter = PipedStreamProviderAdapter(
                listOf(server.url("/").toString(), second.url("/").toString())
            )

            repeat(3) { adapter.search("ambient", YoutubeSearchScope.EVERYTHING) }

            assertEquals(2, server.requestCount)
            assertEquals(1, second.requestCount)
        } finally {
            second.shutdown()
        }
    }

    @Test
    fun providerInstanceAttemptsAreBoundedByTheProvisionalLimit() = runBlocking {
        val second = MockWebServer()
        val third = MockWebServer()
        second.start()
        third.start()
        try {
            server.enqueue(MockResponse().setResponseCode(503))
            second.enqueue(MockResponse().setResponseCode(503))
            third.enqueue(MockResponse().setBody("[]"))
            val adapter = PipedStreamProviderAdapter(
                listOf(server.url("/").toString(), second.url("/").toString(), third.url("/").toString()),
                maxInstanceAttempts = 2
            )

            assertThrows(IOException::class.java) {
                runBlocking { adapter.search("ambient", YoutubeSearchScope.EVERYTHING) }
            }

            assertEquals(1, server.requestCount)
            assertEquals(1, second.requestCount)
            assertEquals(0, third.requestCount)
        } finally {
            second.shutdown()
            third.shutdown()
        }
    }

    @Test
    fun providerCallDeadlineStopsStartingFurtherInstancesUsingFakeClock() = runBlocking {
        var monotonicNow = 0L
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                monotonicNow = 50L
                return MockResponse().setResponseCode(503)
            }
        }
        val second = MockWebServer()
        second.start()
        try {
            second.enqueue(MockResponse().setBody("[]"))
            val adapter = PipedStreamProviderAdapter(
                listOf(server.url("/").toString(), second.url("/").toString()),
                callDeadlineMs = 50L,
                nowEpochMs = { 1_000L },
                monotonicNowMs = { monotonicNow }
            )

            assertThrows(SocketTimeoutException::class.java) {
                runBlocking { adapter.search("ambient", YoutubeSearchScope.EVERYTHING) }
            }

            assertEquals(1, server.requestCount)
            assertEquals(0, second.requestCount)
        } finally {
            second.shutdown()
        }
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
