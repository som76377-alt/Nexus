package com.nexus.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class VinylMotionTest {
    private fun radiusAt(g: VinylMotion.ArmGeometry, angle: Float): Float {
        val radians=Math.toRadians(angle.toDouble())
        val c=kotlin.math.cos(radians).toFloat(); val s=kotlin.math.sin(radians).toFloat()
        val x=g.pivot.x+g.stylusVector.x*c-g.stylusVector.y*s
        val y=g.pivot.y+g.stylusVector.x*s+g.stylusVector.y*c
        return hypot(x,y)
    }

    @Test fun measuredSolverHitsOuterAndInnerGrooveTargetsWithinOneDp() {
        val g=VinylMotion.measuredGeometry()
        assertEquals(131.4f,g.outerTargetRadius,.2f)
        assertEquals(64.2f,g.innerTargetRadius,.2f)
        assertEquals(-26.2f,VinylMotion.startAngle(g),.5f)
        assertEquals(-1.3f,VinylMotion.endAngle(g),.5f)
        assertEquals(g.outerTargetRadius,radiusAt(g,VinylMotion.startAngle(g)),1f)
        assertEquals(g.innerTargetRadius,radiusAt(g,VinylMotion.endAngle(g)),1f)
    }

    @Test fun stylusRadiusMovesMonotonicallyFromOuterToInnerGrooves() {
        val g=VinylMotion.measuredGeometry()
        val start=VinylMotion.startAngle(g); val end=VinylMotion.endAngle(g)
        var previous=radiusAt(g,start)
        for(step in 1..100) {
            val angle=VinylMotion.progressToAngle(step.toLong(),100L,start,end)
            val radius=radiusAt(g,angle)
            assertTrue("radius increased at progress=$step ($radius > $previous)",radius<=previous+.001f)
            previous=radius
        }
        assertTrue(previous>=g.discRadius*2f*VinylMotion.DISC_INNER_GROOVE_RADIUS_FRACTION)
    }

    @Test fun zeroDurationReturnsStartAngle() {
        assertEquals(-26.2f,VinylMotion.progressToAngle(45000L,0L,-26.2f,-1.3f),.001f)
    }

    @Test fun progressClampsAndInterpolates() {
        assertEquals(-1f,VinylMotion.progressToAngle(0L,1000L,-1f,5f),.001f)
        assertEquals(2f,VinylMotion.progressToAngle(500L,1000L,-1f,5f),.001f)
        assertEquals(5f,VinylMotion.progressToAngle(1500L,1000L,-1f,5f),.001f)
    }

    @Test fun speedRampIsMonotonicAndBounded() {
        var up=-1f; var down=2f
        for(step in 0..100) {
            val p=step/100f; val u=VinylMotion.speedRamp(p,true); val d=VinylMotion.speedRamp(p,false)
            assertTrue(u>=up); assertTrue(d<=down); assertTrue(u in 0f..1f); assertTrue(d in 0f..1f)
            up=u; down=d
        }
        assertEquals(0f,VinylMotion.speedRamp(0f,true),0f)
        assertEquals(1f,VinylMotion.speedRamp(1f,true),0f)
    }
}
