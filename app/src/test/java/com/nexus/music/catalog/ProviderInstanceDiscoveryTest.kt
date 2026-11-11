package com.nexus.music.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderInstanceDiscoveryTest {
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
