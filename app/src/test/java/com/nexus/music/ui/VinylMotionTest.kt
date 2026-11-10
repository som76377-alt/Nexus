package com.nexus.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VinylMotionTest {
    @Test fun progressMapsToGrooveAnglesAndClampsOutOfRange() {
        assertEquals(-1f, VinylMotion.progressToAngle(0L, 1000L, -1f, 5f), 0.001f)
        assertEquals(2f, VinylMotion.progressToAngle(500L, 1000L, -1f, 5f), 0.001f)
        assertEquals(5f, VinylMotion.progressToAngle(1000L, 1000L, -1f, 5f), 0.001f)
        assertEquals(-1f, VinylMotion.progressToAngle(-500L, 1000L, -1f, 5f), 0.001f)
        assertEquals(5f, VinylMotion.progressToAngle(1500L, 1000L, -1f, 5f), 0.001f)
    }

    @Test fun unknownDurationHoldsLeadInAngle() {
        assertEquals(1.25f, VinylMotion.progressToAngle(45_000L, 0L, 1.25f, 6f), 0.001f)
    }

    @Test fun spinUpAndDownCurvesAreMonotonicBoundedAndReachTheirTargets() {
        var previousUp = -1f
        var previousDown = 2f
        for (step in 0..100) {
            val progress = step / 100f
            val up = VinylMotion.speedRamp(progress, accelerating = true)
            val down = VinylMotion.speedRamp(progress, accelerating = false)
            assertTrue(up >= previousUp)
            assertTrue(down <= previousDown)
            assertTrue(up >= 0f && up <= 1f)
            assertTrue(down >= 0f && down <= 1f)
            previousUp = up
            previousDown = down
        }
        assertEquals(0f, VinylMotion.speedRamp(0f, true), 0f)
        assertEquals(1f, VinylMotion.speedRamp(1f, true), 0f)
        assertEquals(1f, VinylMotion.speedRamp(0f, false), 0f)
        assertEquals(0f, VinylMotion.speedRamp(1f, false), 0f)
    }

    @Test fun rapidToggleUsesCurrentSpeedAndBlendsWithoutSnapping() {
        assertEquals(VinylMotion.DeckState.SPINNING_UP, VinylMotion.deckStateFor(true, 0.42f))
        assertEquals(VinylMotion.DeckState.SPINNING_DOWN, VinylMotion.deckStateFor(false, 0.42f))
        assertEquals(VinylMotion.DeckState.PLAYING, VinylMotion.deckStateFor(true, 1f))
        assertEquals(VinylMotion.DeckState.STOPPED, VinylMotion.deckStateFor(false, 0f))
        assertEquals(0.42f, VinylMotion.blend(0.42f, 1f, 0f), 0.001f)
        assertEquals(0.71f, VinylMotion.blend(0.42f, 1f, 0.5f), 0.001f)
        assertEquals(0.42f, VinylMotion.blend(0.71f, 0f, 0.5f), 0.001f)
    }
}
