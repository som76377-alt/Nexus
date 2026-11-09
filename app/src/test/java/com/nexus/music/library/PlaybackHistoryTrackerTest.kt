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
        playbackState = Player.STATE_READY
    )

    private fun feed(
        tracker: PlaybackHistoryTracker,
        item: CatalogTrack,
        start: Long,
        end: Long,
        onRecord: (CatalogTrack) -> Unit
    ) {
        for (time in start..end step 500L) tracker.observe(snapshot(item), item, time, onRecord)
    }

    @Test fun recordsOnceAfterTenSecondsOfObservedPlayback() {
        val tracker = PlaybackHistoryTracker()
        val item = track()
        var records = 0
        feed(tracker, item, 0L, 10_000L) { records++ }
        assertEquals(1, records)
        assertFalse(tracker.observe(snapshot(item), item, 10_500L) { records++ })
        assertEquals(1, records)
    }

    @Test fun pausedTimeDoesNotCountTowardThreshold() {
        val tracker = PlaybackHistoryTracker()
        val item = track()
        var records = 0
        feed(tracker, item, 0L, 4_000L) { records++ }
        tracker.observe(snapshot(item, playing = false), item, 4_500L) { records++ }
        tracker.observe(snapshot(item), item, 20_000L) { records++ }
        feed(tracker, item, 20_500L, 26_000L) { records++ }
        assertEquals(1, records)
    }

    @Test fun changingTrackStartsANewTenSecondWindow() {
        val tracker = PlaybackHistoryTracker()
        val first = track()
        val second = track("lmnopqrstuv")
        val recorded = mutableListOf<String>()
        feed(tracker, first, 0L, 5_000L) { recorded += it.url }
        tracker.observe(snapshot(second), second, 5_100L) { recorded += it.url }
        feed(tracker, second, 5_600L, 15_100L) { recorded += it.url }
        assertEquals(listOf(second.url), recorded)
    }

    @Test fun suspendingObservationDoesNotCountTheGap() {
        val tracker = PlaybackHistoryTracker()
        val item = track()
        var records = 0
        feed(tracker, item, 0L, 5_000L) { records++ }
        tracker.suspendObservation()
        tracker.observe(snapshot(item), item, 50_000L) { records++ }
        feed(tracker, item, 50_500L, 55_500L) { records++ }
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
