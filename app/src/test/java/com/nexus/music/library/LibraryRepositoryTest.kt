package com.nexus.music.library

import com.nexus.music.catalog.CatalogTrack
import org.junit.Assert.*
import org.junit.Test

class LibraryRepositoryTest {
    private val repository = InMemoryLibraryRepository()
    private fun track(id: String = "abcdefghijk", title: String = "Track") = CatalogTrack(
        url = "https://www.youtube.com/watch?v=$id",
        title = title,
        artist = "Artist",
        thumbnailUrl = "https://img.example/$id.jpg",
        durationSeconds = 185L,
        viewCount = null
    )

    @Test fun likesAndSavedTracksAreIndependentAndIdempotent() {
        val item = track()
        assertTrue(repository.setLiked(item, true))
        assertTrue(repository.setLiked(item, true))
        assertTrue(repository.isLiked("abcdefghijk"))
        assertTrue(repository.setSaved(item, true))
        assertTrue(repository.isSaved("abcdefghijk"))
        assertEquals(1, repository.likedTracks().size)
        assertTrue(repository.setLiked(item, false))
        assertFalse(repository.isLiked("abcdefghijk"))
        assertTrue(repository.isSaved("abcdefghijk"))
    }

    @Test fun playlistsSupportCreateRenameDeleteAndUniqueNames() {
        val id = repository.createPlaylist("Road trip")
        assertTrue(repository.renamePlaylist(id, "Late night"))
        assertEquals("Late night", repository.playlists().single().name)
        try {
            repository.createPlaylist(" late NIGHT ")
            fail("Duplicate names should be rejected without case sensitivity.")
        } catch (_: IllegalArgumentException) { }
        assertTrue(repository.deletePlaylist(id))
        assertFalse(repository.deletePlaylist(id))
    }

    @Test fun playlistItemsAreUniqueAndCanBeRemoved() {
        val id = repository.createPlaylist("Focus")
        val item = track()
        assertTrue(repository.addToPlaylist(id, item))
        assertTrue(repository.addToPlaylist(id, item))
        assertEquals(1, repository.playlists().single().trackCount)
        assertEquals("abcdefghijk", repository.playlistTracks(id).single().id)
        assertTrue(repository.removeFromPlaylist(id, "abcdefghijk"))
        assertFalse(repository.removeFromPlaylist(id, "abcdefghijk"))
    }

    @Test fun historyIsNewestFirstAndBounded() {
        repository.recordHistory(track("abcdefghijk", "Older"), 100L)
        repository.recordHistory(track("lmnopqrstuv", "Newer"), 200L)
        assertEquals(listOf("Newer", "Older"), repository.history().map { it.track.title })
        assertEquals(1, repository.history(1).size)
    }

    @Test fun invalidVideoUrlsAreNotStored() {
        assertFalse(repository.setLiked(track("not-a-video-id"), true))
        assertFalse(repository.recordHistory(track("not-a-video-id"), 100L))
        assertTrue(repository.likedTracks().isEmpty())
    }

    @Test fun canonicalVideoIdsSupportWatchShortsEmbedAndShortHosts() {
        assertEquals("abcdefghijk", YoutubeVideoId.fromUrl("https://www.youtube.com/watch?v=abcdefghijk&list=abc"))
        assertEquals("abcdefghijk", YoutubeVideoId.fromUrl("https://youtu.be/abcdefghijk"))
        assertEquals("abcdefghijk", YoutubeVideoId.fromUrl("https://youtube.com/shorts/abcdefghijk"))
        assertEquals("abcdefghijk", YoutubeVideoId.fromUrl("https://youtube.com/embed/abcdefghijk"))
        assertNull(YoutubeVideoId.fromUrl("https://example.com/watch?v=abcdefghijk"))
    }
}
