package com.nexus.music.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YoutubeVideoIdTest {
    private val id = "abcdefghijk"

    @Test
    fun extractsWatchIdsWithExtraQueryParameters() {
        assertEquals(id, YoutubeVideoId.fromUrl("https://www.youtube.com/watch?v=$id&list=PL123&t=45"))
    }

    @Test
    fun acceptsMobileAndMusicHosts() {
        assertEquals(id, YoutubeVideoId.fromUrl("https://m.youtube.com/watch?v=$id"))
        assertEquals(id, YoutubeVideoId.fromUrl("https://music.youtube.com/watch?v=$id"))
    }

    @Test
    fun acceptsShortLinksAndAlternateWatchPaths() {
        assertEquals(id, YoutubeVideoId.fromUrl("https://youtu.be/$id?t=30"))
        assertEquals(id, YoutubeVideoId.fromUrl("https://www.youtube.com/shorts/$id"))
        assertEquals(id, YoutubeVideoId.fromUrl("https://www.youtube.com/embed/$id"))
        assertEquals(id, YoutubeVideoId.fromUrl("https://www.youtube.com/live/$id"))
    }

    @Test
    fun acceptsPipedRelativeWatchPathWithoutRewritingIt() {
        val original = "/watch?v=$id&list=PL123"
        val track = CatalogTrack(original, "Track", null, null, null, null)

        assertEquals(id, track.videoId)
        assertEquals(original, track.url)
    }

    @Test
    fun rejectsUnknownHostsAndInvalidIds() {
        assertNull(YoutubeVideoId.fromUrl("https://example.com/watch?v=$id"))
        assertNull(YoutubeVideoId.fromUrl("https://www.youtube.com/watch?v=short"))
        assertNull(YoutubeVideoId.fromUrl("https://youtube.com.evil.example/watch?v=$id"))
    }
}
