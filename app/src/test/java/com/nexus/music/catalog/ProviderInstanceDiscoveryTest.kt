package com.nexus.music.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Test
import org.junit.Assert.assertNotNull
import java.util.concurrent.TimeUnit

class ProviderInstanceDiscoveryTest {
    @Test
    fun directoryFetchReturnsResponseBody() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("directory"))
            val body = ProviderInstanceDiscovery.fetchDirectory(OkHttpClient(), server.url("/").toString())
            assertEquals("directory", body)
            assertEquals("GET", server.takeRequest().method)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun cancellingDirectoryFetchCancelsTheInFlightHttpCall() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val request = async(Dispatchers.IO) {
                ProviderInstanceDiscovery.fetchDirectory(OkHttpClient(), server.url("/").toString())
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            withTimeout(1_000L) {
                request.cancelAndJoin()
            }
            assertEquals(true, request.isCancelled)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun pipedDirectoryReadsOnlyApiColumnAndDeduplicatesHttpsEndpoints() {
        val markdown = """
            | Instance Name | Instance API URL | Location |
            | --- | --- | --- |
            | [Example](https://piped.example) | [API](https://pipedapi.example) | 🇮🇳 |
            | Other | https://api-piped.example | 🇩🇪 |
            | Bad | http://insecure.example | 🇺🇸 |
        """.trimIndent()

        assertEquals(
            listOf("https://pipedapi.example", "https://api-piped.example"),
            ProviderInstanceDiscovery.parsePipedInstances(markdown)
        )
    }

    @Test
    fun invidiousDirectoryRequiresApiCapabilityAndHttps() {
        val json = """
            [
              ["https://inv-one.example", {"api": true, "type": "https"}],
              ["https://web-only.example", {"api": false, "type": "https"}],
              ["http://insecure.example", {"api": true, "type": "http"}],
              ["https://inv-two.example/", {"api": true, "type": "https"}]
            ]
        """.trimIndent()

        assertEquals(
            listOf("https://inv-one.example", "https://inv-two.example"),
            ProviderInstanceDiscovery.parseInvidiousInstances(json)
        )
    }

    @Test
    fun endpointNormalizerRejectsCredentialsQueriesAndNonHttps() {
        assertEquals(null, ProviderInstanceDiscovery.normalizeEndpoint("http://plain.example"))
        assertEquals(null, ProviderInstanceDiscovery.normalizeEndpoint("https://user@host.example"))
        assertEquals(null, ProviderInstanceDiscovery.normalizeEndpoint("https://host.example/?token=x"))
        assertFalse(ProviderInstanceDiscovery.normalizeEndpoint("not a url") != null)
    }
}
