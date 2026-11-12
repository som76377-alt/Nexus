package com.nexus.music.catalog

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class StreamProbeTest {
    private val server = MockWebServer().apply { start() }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun probesWithSingleByteRangeAndCarriesResolvedIpFamily() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-0/128")
                .setBody("x")
        )
        val probe = StreamProbe(
            systemDns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        )
        val audio = sampleAudio(server.url("/audio").toString())

        val result = probe.probe(audio)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("bytes=0-0", request.getHeader("Range"))
        assertEquals(StreamIpFamily.IPV4, result.ipFamily)
    }

    @Test
    fun acceptsServersThatIgnoreRangeButReturnData() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("audio"))
        val probe = StreamProbe(
            systemDns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        )

        val result = probe.probe(sampleAudio(server.url("/audio").toString()))

        assertEquals(StreamIpFamily.IPV4, result.ipFamily)
    }

    private fun sampleAudio(url: String) = ResolvedAudio(
        sourceUrl = "https://youtube.com/watch?v=abcdefghijk",
        title = "Track",
        artist = null,
        thumbnailUrl = null,
        durationSeconds = 60L,
        audioUrl = url,
        averageBitrate = 128_000,
        mimeType = "audio/mp4",
        requestHeaders = mapOf("X-Stream-Test" to "nexus")
    )
}
