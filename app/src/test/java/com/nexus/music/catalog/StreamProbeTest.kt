package com.nexus.music.catalog

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException

class StreamProbeTest {
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
    fun sendsSingleByteRangeGetAndAcceptsPartialAudioResponse() {
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "audio/mp4")
                    .setHeader("Content-Range", "bytes 0-0/1024")
                    .setBody("x")
            )
            val audio = resolvedAudio(server.url("/audio").toString()).copy(
                requestHeaders = mapOf("Referer" to "https://www.youtube.com/")
            )

            var responseCode: Int? = null
            val result = StreamProbe(allowHttpForTests = true).validate(audio) { responseCode = it }
            val request = server.takeRequest()

            assertEquals(audio, result)
            assertEquals(206, responseCode)
            assertEquals("GET", request.method)
            assertEquals("bytes=0-0", request.getHeader("Range"))
            assertEquals("https://www.youtube.com/", request.getHeader("Referer"))
        }
    }

    @Test
    fun preservesRejectedStreamHttpStatusForProviderCooldown() {
        val error = assertThrows(ProviderHttpException::class.java) {
            runBlocking {
                server.enqueue(MockResponse().setResponseCode(403))
                StreamProbe(allowHttpForTests = true)
                    .validate(resolvedAudio(server.url("/audio").toString()))
            }
        }
        assertEquals(403, error.statusCode)
        assertEquals(StreamErrorClass.ACCESS_BLOCKED, StreamErrorClassifier.classify(error))
    }

    @Test
    fun rejectsNonAudioResponse() {
        assertThrows(IOException::class.java) {
            runBlocking {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/html")
                        .setBody("blocked")
                )
                StreamProbe(allowHttpForTests = true)
                    .validate(resolvedAudio(server.url("/audio").toString()))
            }
        }
    }

    private fun resolvedAudio(url: String) = ResolvedAudio(
        sourceUrl = "https://youtube.com/watch?v=abcdefghijk",
        title = "Track",
        artist = "Artist",
        thumbnailUrl = null,
        durationSeconds = 60L,
        audioUrl = url,
        averageBitrate = 128_000,
        mimeType = "audio/mp4"
    )
}
