package com.nexus.music.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogUrlValidatorTest {
    @Test
    fun acceptsHttpsUrlsWithPathsAndSignedQueryParameters() {
        assertTrue(
            CatalogUrlValidator.isValidHttpsUrl(
                "https://media.example/audio.m4a?signature=abc123&expires=12345"
            )
        )
    }

    @Test
    fun rejectsCleartextMalformedRelativeAndHostlessUrls() {
        listOf(
            "http://media.example/audio.m4a",
            "https://",
            "file:///tmp/audio.m4a",
            "/relative/audio.m4a",
            "https:///missing-host"
        ).forEach { url ->
            assertFalse("Expected URL to be rejected: $url", CatalogUrlValidator.isValidHttpsUrl(url))
        }
    }
}
