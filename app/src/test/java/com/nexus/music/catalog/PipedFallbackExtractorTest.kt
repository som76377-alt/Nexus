package com.nexus.music.catalog

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipedFallbackExtractorTest {
    @Test
    fun parsesMaintainedDirectoryAndKeepsOnlyHttpsApiUrls() {
        val directory = """
            | Name | API URL | Location | CDN |
            | --- | --- | --- | --- |
            | India | https://pipedapi.in.example.org | IN | https://cdn.example.org |
            | Europe | [API](https://pipedapi.eu.example.org/) | EU | https://cdn.example.org |
            | Insecure | http://pipedapi.bad.example.org | XX | https://cdn.example.org |
            | Invalid | not-a-url | XX | https://cdn.example.org |
        """.trimIndent()

        assertEquals(
            listOf(
                "https://pipedapi.in.example.org",
                "https://pipedapi.eu.example.org"
            ),
            PipedFallbackExtractor.parseInstanceDirectory(directory)
        )
    }

    @Test
    fun searchMapsPipedResultsToCatalogTracks() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "items": [
                    {
                      "url": "/watch?v=abcdefghijk",
                      "title": "Sample track",
                      "uploaderName": "Sample artist",
                      "thumbnail": "https://img.example.com/track.jpg",
                      "duration": 184,
                      "views": 12345
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        server.start()
        try {
            val extractor = PipedFallbackExtractor(
                client = OkHttpClient(),
                instanceUrls = listOf(server.url("/").toString().removeSuffix("/"))
            )
            val results = extractor.search("sample track", YoutubeSearchScope.MUSIC_SONGS)

            assertEquals(1, results.size)
            assertEquals("https://www.youtube.com/watch?v=abcdefghijk", results.single().url)
            assertEquals("Sample track", results.single().title)
            assertEquals("Sample artist", results.single().artist)
            assertEquals(184L, results.single().durationSeconds)
            assertTrue(server.takeRequest().path.orEmpty().startsWith("/search?"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun audioResolutionSelectsHighestBitrateDirectAudioStream() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "title": "Sample track",
                  "uploader": "Sample artist",
                  "duration": 184,
                  "thumbnailUrl": "https://img.example.com/track.jpg",
                  "audioStreams": [
                    {
                      "url": "https://cdn.example.com/low.m4a",
                      "mimeType": "audio/mp4",
                      "bitrate": 64000
                    },
                    {
                      "url": "https://cdn.example.com/high.m4a",
                      "mimeType": "audio/mp4",
                      "bitrate": 128000
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        server.start()
        try {
            val extractor = PipedFallbackExtractor(
                client = OkHttpClient(),
                instanceUrls = listOf(server.url("/").toString().removeSuffix("/"))
            )
            val result = extractor.resolveAudio("https://www.youtube.com/watch?v=abcdefghijk")

            assertEquals("https://cdn.example.com/high.m4a", result.audioUrl)
            assertEquals(128000, result.averageBitrate)
            assertEquals("Sample track", result.title)
            assertTrue(server.takeRequest().path.orEmpty().startsWith("/streams/abcdefghijk"))
        } finally {
            server.shutdown()
        }
    }
}
