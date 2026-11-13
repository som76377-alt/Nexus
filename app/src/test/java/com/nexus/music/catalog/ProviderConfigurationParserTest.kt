package com.nexus.music.catalog

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderConfigurationParserTest {
    @Test
    fun parsesEnabledProvidersInConfiguredOrderAndIgnoresUnknownKeys() {
        val config = ProviderConfigurationParser.parse(
            """{"version":2,"note":"ignored","providers":[{"id":"piped","enabled":true,"extra":"ignored"},{"id":"newpipe","enabled":false},{"id":"invidious","enabled":true}]}"""
        )
        assertEquals(2, config.version)
        assertEquals(listOf(StreamProvider.PIPED, StreamProvider.INVIDIOUS), config.enabledOrder)
    }

    @Test
    fun rejectsUnknownProviderAndDuplicateEntriesAsWholeConfig() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderConfigurationParser.parse("""{"version":1,"providers":[{"id":"unknown","enabled":true}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderConfigurationParser.parse("""{"version":1,"providers":[{"id":"piped","enabled":true},{"id":"piped","enabled":false}]}""")
        }
    }

    @Test
    fun rejectsInvalidVersionShapeAndAllDisabledConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderConfigurationParser.parse("""{"version":0,"providers":[{"id":"newpipe","enabled":true}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderConfigurationParser.parse("""{"version":1,"providers":[{"id":"newpipe","enabled":"true"}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderConfigurationParser.parse("""{"version":1,"providers":[{"id":"newpipe","enabled":false}]}""")
        }
    }

    @Test
    fun sendsEtagAndHandlesNotModifiedResponse() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(304))
            val result = RemoteProviderConfigFetcher(
                OkHttpClient(),
                server.url("/providers.json").toString()
            ).fetch("\"v1\"")
            assertEquals(ProviderConfigResponse.NotModified, result)
            assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fetchReturnsBodyAndEtagForValidHttpResponse() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("ETag", "\"v2\"")
                    .setBody("""{"version":2,"providers":[{"id":"newpipe","enabled":true}]}""")
            )
            val result = RemoteProviderConfigFetcher(
                OkHttpClient(),
                server.url("/providers.json").toString()
            ).fetch(null)
            assertEquals(
                ProviderConfigResponse.Updated(
                    """{"version":2,"providers":[{"id":"newpipe","enabled":true}]}""",
                    "\"v2\""
                ),
                result
            )
        } finally {
            server.shutdown()
        }
    }
}
