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
    fun audioResolutionRequestsInstanceProxiedFormat() {
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
                      "url": "https://invidious.example.com/latest_version?id=abcdefghijk&itag=140",
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

            assertEquals("https://invidious.example.com/latest_version?id=abcdefghijk&itag=140", result.audioUrl)
            assertEquals("audio/mp4", result.mimeType)
            assertEquals(128000, result.averageBitrate)
            assertEquals("Sample track", result.title)
            val requestPath = server.takeRequest().path.orEmpty()
            assertTrue(requestPath.startsWith("/api/v1/videos/abcdefghijk"))
            assertTrue(requestPath.contains("region=IN"))
            assertTrue(requestPath.contains("local=true"))
        } finally {
            server.shutdown()
        }
    }
    @Test
    fun maintainedDirectorySelectsOnlyHealthyHttpsApiEnabledInstances() {
        val directory = """
            [
              ["api-disabled", {
                "uri": "https://disabled.example.org",
                "type": "https",
                "api": false,
                "monitor": {"down": false, "uptime": 99.99}
              }],
              ["http-api", {
                "uri": "http://insecure.example.org",
                "type": "http",
                "api": true,
                "monitor": {"down": false, "uptime": 99.99}
              }],
              ["down-api", {
                "uri": "https://down.example.org",
                "type": "https",
                "api": true,
                "monitor": {"down": true, "uptime": 99.99}
              }],
              ["healthy-api", {
                "uri": "https://healthy.example.org",
                "type": "https",
                "api": true,
                "monitor": {"down": false, "uptime": 98.5}
              }],
              ["best-api", {
                "uri": "https://best.example.org/",
                "type": "https",
                "api": true,
                "monitor": {"down": false, "uptime": 99.9}
              }]
            ]
        """.trimIndent()

        assertEquals(
            listOf("https://best.example.org", "https://healthy.example.org"),
            InvidiousFallbackExtractor.parseMaintainedInstances(directory)
        )
    }

}
