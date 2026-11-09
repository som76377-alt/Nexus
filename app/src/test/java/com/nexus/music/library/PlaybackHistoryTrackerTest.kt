package com.nexus.music.library

import androidx.media3.common.Player
import com.nexus.music.catalog.CatalogTrack
import com.nexus.music.playback.PlaybackSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHistoryTrackerTest {
    private fun track(id: String = "abcdefghijk") = CatalogTrack(
        url = "https://www.youtube.com/watch?v=$id",
        title = "Track $id",
        artist = "Artist",
        thumbnailUrl = null,
        durationSeconds = 180L,
        viewCount = null
    )

    private fun snapshot(track: CatalogTrack, playing: Boolean = true) = PlaybackSnapshot(
        connected = true,
        sourceId = track.url,
        title = track.title,
        artist = track.artist.orEmpty(),
        isPlaying = playing,
        playbackState = if (playing) Player.STATE_READY else Player.STATE_READY
    )

    @Test fun recordsOnceAfterTenSecondsOfObservedPlayback() {
        val tracker = PlaybackHistoryTracker()
        val item = track()
        var records = 0
        for (time in 0L..10_000L step 500L) {
            tracker.observe(snapshot(item), item, time) { records++ }
        }
        assertEquals(1, records)
        assertFalse(tracker.observe(snapshot(item), item, 10_500L) { records++ })
        assertEquals(1, records)
    }

    @Test fun pausedTimeDoesNotCountTowardThreshold() {
        val tracker = PlaybackHistoryTracker()
        val item = track()
        var records = 0
        tracker.observe(snapshot(item), item, 0L) { records++ }
        tracker.observe(snapshot(item), item, 4_000L) { records++ }
        tracker.observe(snapshot(item, playing = false), item, 5_000L) { records++ }
        tracker.observe(snapshot(item), item, 20_000L) { records++ }
        tracker.observe(snapshot(item), item, 26_000L) { records++ }
        assertEquals(1, records)
    }

    @Test fun changingTrackStartsANewTenSecondWindow() {
        val tracker = PlaybackHistoryTracker()
        val first = track()
        val second = track("lmnopqrstuv")
        var recorded = mutableListOf<String>()
        tracker.observe(snapshot(first), first, 0L) { recorded += it.url }
        tracker.observe(snapshot(first), first, 5_000L) { recorded += it.url }
        tracker.observe(snapshot(second), second, 5_100L) { recorded += it.url }
        tracker.observe(snapshot(second), second, 10_000L) { recorded += it.url }
        assertTrue(recorded.isEmpty())
        tracker.observe(snapshot(second), second, 15_100L) { recorded += it.url }
        assertEquals(listOf(second.url), recorded)
    }

    @Test fun suspendingObservationDoesNotCountTheGap() {
        val tracker = PlaybackHistoryTracker()
        val item = track()
        var records = 0
        tracker.observe(snapshot(item), item, 0L) { records++ }
        tracker.observe(snapshot(item), item, 5_000L) { records++ }
        tracker.suspendObservation()
        tracker.observe(snapshot(item), item, 50_000L) { records++ }
        tracker.observe(snapshot(item), item, 55_000L) { records++ }
        assertEquals(1, records)
    }

    @Test fun mismatchedOrInvalidSourceIsNotRecorded() {
        val tracker = PlaybackHistoryTracker()
        val item = track()
        val wrongSource = snapshot(item).copy(sourceId = "https://example.com/not-youtube")
        var records = 0
        tracker.observe(wrongSource, item, 0L) { records++ }
        tracker.observe(wrongSource, item, 20_000L) { records++ }
        assertEquals(0, records)
    }
}
