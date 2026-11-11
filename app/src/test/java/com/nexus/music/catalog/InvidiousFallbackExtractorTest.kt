package com.nexus.music.catalog

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvidiousFallbackExtractorTest {
    @Test
    fun searchMapsVideoResultsToCatalogTracks() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "type": "video",
                    "videoId": "abcdefghijk",
                    "title": "Sample track",
                    "author": "Sample artist",
                    "lengthSeconds": 184,
                    "viewCount": 12345,
                    "videoThumbnails": [
                      {"url": "https://img.example.com/track.jpg"}
                    ]
                  },
                  {
                    "type": "channel",
                    "author": "Ignored channel"
                  }
                ]
                """.trimIndent()
            )
        )
        server.start()
        try {
            val extractor = InvidiousFallbackExtractor(
                client = OkHttpClient(),
                instanceUrls = listOf(server.url("/").toString().removeSuffix("/"))
            )

            val results = extractor.search("sample track", YoutubeSearchScope.MUSIC_SONGS)

            assertEquals(1, results.size)
            assertEquals("https://www.youtube.com/watch?v=abcdefghijk", results.single().url)
            assertEquals("Sample track", results.single().title)
            assertEquals("Sample artist", results.single().artist)
            assertEquals(184L, results.single().durationSeconds)
            assertTrue(server.takeRequest().path.orEmpty().startsWith("/api/v1/search?"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun audioResolutionSelectsDirectHttpsAudioFormat() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "title": "Sample track",
                  "author": "Sample artist",
                  "lengthSeconds": 184,
                  "videoThumbnails": [
                    {"url": "https://img.example.com/track.jpg"}
                  ],
                  "adaptiveFormats": [
                    {
                      "type": "video/mp4",
                      "url": "https://cdn.example.com/video.mp4",
                      "bitrate": "900000"
                    },
                    {
                      "type": "audio/mp4",
                      "url": "https://cdn.example.com/audio.m4a",
                      "bitrate": "128000"
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        server.start()
        try {
            val extractor = InvidiousFallbackExtractor(
                client = OkHttpClient(),
                instanceUrls = listOf(server.url("/").toString().removeSuffix("/"))
            )

            val result = extractor.resolveAudio("https://www.youtube.com/watch?v=abcdefghijk")

            assertEquals("https://cdn.example.com/audio.m4a", result.audioUrl)
            assertEquals("audio/mp4", result.mimeType)
            assertEquals(128000, result.averageBitrate)
            assertEquals("Sample track", result.title)
            assertTrue(server.takeRequest().path.orEmpty().startsWith("/api/v1/videos/abcdefghijk"))
        } finally {
            server.shutdown()
        }
    }
}
