package com.nexus.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCachePolicyTest {
    @Test
    fun cacheKeyUsesCanonicalVideoIdAndItagNotExpiringStreamUrl() {
        val source = "https://www.youtube.com/watch?v=abcDEF_1234"
        val firstUrl = "https://rr.googlevideo.com/audio?expire=1800000000&itag=251&sig=first"
        val refreshedUrl = "https://rr.googlevideo.com/audio?expire=1800000300&itag=251&sig=second"

        assertEquals("abcDEF_1234:251", AudioCachePolicy.cacheKey(source, firstUrl))
        assertEquals(
            AudioCachePolicy.cacheKey(source, firstUrl),
            AudioCachePolicy.cacheKey(source, refreshedUrl)
        )
    }

    @Test
    fun supportsShortsAndShortLinksAndRejectsUnknownItag() {
        assertEquals(
            "abcDEF_1234:140",
            AudioCachePolicy.cacheKey(
                "https://youtu.be/abcDEF_1234",
                "https://media.example/audio?itag=140"
            )
        )
        assertEquals(
            "abcDEF_1234:140",
            AudioCachePolicy.cacheKey(
                "https://youtube.com/shorts/abcDEF_1234",
                "https://media.example/audio?itag=140"
            )
        )
        assertNull(
            AudioCachePolicy.cacheKey(
                "https://youtube.com/watch?v=abcDEF_1234",
                "https://media.example/audio"
            )
        )
    }

    @Test
    fun parsesGoogleVideoExpiryAndDefaultsToThirtyMinutes() {
        assertEquals(
            1_800_000_000_000L,
            AudioCachePolicy.expiryEpochMs(
                "https://media.example/audio?expire=1800000000&itag=251",
                1_700_000_000_000L
            )
        )
        assertEquals(
            1_700_000_000_000L + AudioCachePolicy.DEFAULT_URL_TTL_MS,
            AudioCachePolicy.expiryEpochMs("https://media.example/audio?itag=251", 1_700_000_000_000L)
        )
    }

    @Test
    fun urlBecomesStaleTenMinutesBeforeExpiry() {
        val expiry = 2_000_000_000_000L
        assertTrue(AudioCachePolicy.isUrlFresh(expiry, expiry - 600_001L))
        assertFalse(AudioCachePolicy.isUrlFresh(expiry, expiry - 600_000L))
        assertFalse(AudioCachePolicy.isUrlFresh(expiry, expiry))
    }

    @Test
    fun onlyKnownCompleteContentLengthCountsAsFullyCached() {
        assertTrue(AudioCachePolicy.isFullyCached(10_000L, 10_000L))
        assertTrue(AudioCachePolicy.isFullyCached(10_000L, 12_000L))
        assertFalse(AudioCachePolicy.isFullyCached(10_000L, 9_999L))
        assertFalse(AudioCachePolicy.isFullyCached(-1L, 100_000L))
        assertFalse(AudioCachePolicy.isFullyCached(0L, 0L))
    }
}
