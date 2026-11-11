package com.nexus.music.catalog

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

class OkHttpDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var downloader: OkHttpDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = OkHttpDownloader()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getForwardsHeadersAndReturnsBodyAndStatus() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("catalogue-response"))

        val response = downloader.execute(
            Request.newBuilder()
                .get(server.url("/search").toString())
                .setHeader("Accept-Language", "en-IN")
                .setHeader("X-Test-Header", "nexus")
                .build()
        )

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("en-IN", recorded.getHeader("Accept-Language"))
        assertEquals("nexus", recorded.getHeader("X-Test-Header"))
        assertTrue(recorded.getHeader("User-Agent")!!.contains("Nexus/0.1"))
        assertEquals(200, response.responseCode())
        assertEquals("catalogue-response", response.responseBody())
        assertEquals(server.url("/search").toString(), response.latestUrl())
    }

    @Test
    fun postForwardsBodyAndMethod() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("created"))
        val payload = """{"query":"test"}""".toByteArray()

        val response = downloader.execute(
            Request.newBuilder()
                .post(server.url("/internal").toString(), payload)
                .build()
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(payload.decodeToString(), recorded.body.readUtf8())
        assertEquals(201, response.responseCode())
    }

    @Test(expected = ReCaptchaException::class)
    fun rateLimitIsSurfacedAsRecaptchaException() {
        server.enqueue(MockResponse().setResponseCode(429))
        downloader.execute(
            Request.newBuilder()
                .get(server.url("/rate-limited").toString())
                .build()
        )
    }
}
