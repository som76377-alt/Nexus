package com.nexus.music.catalog

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeCatalogRepositoryFallbackTest {
    @Test
    fun pipedSearchAndAudioResolutionAreUsedWhenPrimaryCircuitIsOpen() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "items": [
                    {
                      "url": "/watch?v=abcdefghijk",
                      "title": "Piped result",
                      "uploaderName": "Piped artist",
                      "duration": 184
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "title": "Piped result",
                  "uploader": "Piped artist",
                  "duration": 184,
                  "audioStreams": [
                    {
                      "url": "https://cdn.example.com/piped.m4a",
                      "mimeType": "audio/mp4",
                      "format": "M4A",
                      "bitrate": 128000
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        server.start()
        try {
            val base = server.url("/").toString().removeSuffix("/")
            val repository = YoutubeCatalogRepository(
                downloader = OkHttpDownloader(),
                pipedFallback = PipedFallbackExtractor(
                    client = OkHttpClient(),
                    instanceUrls = listOf(base)
                ),
                invidiousFallback = InvidiousFallbackExtractor(
                    client = OkHttpClient(),
                    instanceUrls = emptyList()
                ),
                failoverPolicy = ExtractorFailoverPolicy().apply { primaryFailed() }
            )

            val results = repository.search("sample", YoutubeSearchScope.MUSIC_SONGS)
            assertEquals(1, results.size)
            assertEquals("Piped result", results.single().title)
            val audio = repository.resolveAudio(results.single().url)
            assertEquals("https://cdn.example.com/piped.m4a", audio.audioUrl)
            assertTrue(repository.diagnosticsSummary().contains("Piped: OK"))
            assertTrue(repository.diagnosticsSummary().contains("NewPipe: Not tried yet"))
            assertEquals("/search?q=sample&filter=music_songs", server.takeRequest().path)
            assertEquals("/streams/abcdefghijk", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun invidiousIsUsedWhenPipedHasNoAvailableInstances() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "type": "video",
                    "videoId": "abcdefghijk",
                    "title": "Invidious result",
                    "author": "Invidious artist",
                    "lengthSeconds": 184
                  }
                ]
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "title": "Invidious result",
                  "author": "Invidious artist",
                  "lengthSeconds": 184,
                  "adaptiveFormats": [
                    {
                      "type": "audio/mp4",
                      "url": "https://cdn.example.com/invidious.m4a",
                      "bitrate": "128000"
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        server.start()
        try {
            val base = server.url("/").toString().removeSuffix("/")
            val repository = YoutubeCatalogRepository(
                downloader = OkHttpDownloader(),
                pipedFallback = PipedFallbackExtractor(
                    client = OkHttpClient(),
                    instanceUrls = emptyList()
                ),
                invidiousFallback = InvidiousFallbackExtractor(
                    client = OkHttpClient(),
                    instanceUrls = listOf(base)
                ),
                failoverPolicy = ExtractorFailoverPolicy().apply { primaryFailed() }
            )

            val results = repository.search("sample", YoutubeSearchScope.MUSIC_SONGS)
            assertEquals(1, results.size)
            assertEquals("Invidious result", results.single().title)
            val audio = repository.resolveAudio(results.single().url)
            assertEquals("https://cdn.example.com/invidious.m4a", audio.audioUrl)
            assertTrue(repository.diagnosticsSummary().contains("Invidious: OK"))
            assertTrue(repository.diagnosticsSummary().contains("NewPipe: Not tried yet"))
            assertTrue(server.takeRequest().path.orEmpty().startsWith("/api/v1/search?"))
            assertTrue(server.takeRequest().path.orEmpty().startsWith("/api/v1/videos/abcdefghijk"))
        } finally {
            server.shutdown()
        }
    }
}
