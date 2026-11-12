package com.nexus.music.playback

import android.net.Uri
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRequestHeadersTest {
    @Test
    fun headersAreReplacedWhenActiveTrackChanges() {
        val first = "https://media.example/one"
        StreamRequestHeaders.replace(first, mapOf("Referer" to "https://www.youtube.com/"))
        assertEquals(mapOf(first to mapOf("Referer" to "https://www.youtube.com/")), StreamRequestHeaders.snapshotForTests())
        StreamRequestHeaders.replace("https://media.example/two", emptyMap())
        assertTrue(StreamRequestHeaders.snapshotForTests().isEmpty())
    }

    @Test
    fun headersApplyOnlyToMatchingStreamUrl() {
        val url = "https://media.example/audio"
        StreamRequestHeaders.replace(url, mapOf("Referer" to "https://www.youtube.com/"))
        val matching = StreamRequestHeaders.resolve(DataSpec(Uri.parse(url)))
        val other = StreamRequestHeaders.resolve(DataSpec(Uri.parse("https://other.example/audio")))
        assertEquals(mapOf("Referer" to "https://www.youtube.com/"), matching.httpRequestHeaders)
        assertTrue(other.httpRequestHeaders.isEmpty())
    }
}
