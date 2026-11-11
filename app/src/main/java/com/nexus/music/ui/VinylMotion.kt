package com.nexus.music.ui

import kotlin.math.*

/** Pure motion math for the vinyl player; kept free of Android APIs for JVM tests. */
internal object VinylMotion {
    enum class DeckState { STOPPED, SPINNING_UP, PLAYING, SPINNING_DOWN }
    enum class ArmPhase { RESTING, LIFTING, SWINGING_TO_GROOVE, LOWERING, TRACKING, SWINGING_TO_REST }

    // measured from the PNG, +/-2px
    const val TONEARM_PIVOT_X_FRACTION = 210f / 276f
    const val TONEARM_PIVOT_Y_FRACTION = 73f / 348f
    const val TONEARM_STYLUS_X_FRACTION = 63f / 276f
    const val TONEARM_STYLUS_Y_FRACTION = 320f / 348f
    const val DISC_OUTER_GROOVE_RADIUS_FRACTION = 432f / 912f
    const val DISC_INNER_GROOVE_RADIUS_FRACTION = 192f / 912f
    const val DISC_LABEL_RADIUS_FRACTION = 156f / 912f

    data class Point(val x: Float, val y: Float)
    data class ArmGeometry(val pivot: Point, val stylusVector: Point, val discRadius: Float) {
        val armLength get() = hypot(stylusVector.x, stylusVector.y)
        val outerTargetRadius get() = discRadius * DISC_OUTER_GROOVE_RADIUS_FRACTION * 0.97f
        val innerTargetRadius get() = discRadius * DISC_INNER_GROOVE_RADIUS_FRACTION + 4f
    }

    fun measuredGeometry(discSizeDp: Float = 286f, armWidthDp: Float = 152f, armHeightDp: Float = 191f,
        translationXDp: Float = 101f, translationYDp: Float = -69f): ArmGeometry {
        val pivotLocal = Point(TONEARM_PIVOT_X_FRACTION * armWidthDp, TONEARM_PIVOT_Y_FRACTION * armHeightDp)
        val tipLocal = Point(TONEARM_STYLUS_X_FRACTION * armWidthDp, TONEARM_STYLUS_Y_FRACTION * armHeightDp)
        return ArmGeometry(
            Point(translationXDp + pivotLocal.x - discSizeDp / 2f, translationYDp + pivotLocal.y - discSizeDp / 2f),
            Point(tipLocal.x - pivotLocal.x, tipLocal.y - pivotLocal.y),
            discSizeDp / 2f
        )
    }

    /** Positive View.rotation is clockwise in screen coordinates. */
    fun solveGrooveAngle(g: ArmGeometry, targetRadius: Float): Float {
        val px=g.pivot.x; val py=g.pivot.y; val d=hypot(px,py); val length=g.armLength
        require(d > 0f && abs(length-targetRadius) <= d && d <= length+targetRadius) { "Tonearm/groove circles do not intersect" }
        val a=(length*length-targetRadius*targetRadius+d*d)/(2f*d)
        val h=sqrt((length*length-a*a).coerceAtLeast(0f))
        val ux=-px/d; val uy=-py/d; val bx=px+a*ux; val by=py+a*uy
        val intersections=listOf(Point(bx-h*uy,by+h*ux),Point(bx+h*uy,by-h*ux))
        val base=atan2(g.stylusVector.y,g.stylusVector.x)
        val angles=intersections.map { p ->
            val raw=Math.toDegrees((atan2(p.y-py,p.x-px)-base).toDouble()).toFloat()
            ((raw+540f)%360f)-180f
        }
        // Select the near-zero, clockwise/playing-side intersection, not the far-side one.
        return angles.filter { it in -90f..15f }.minByOrNull { abs(it) }
            ?: error("No playing-side tonearm/groove intersection")
    }
    fun startAngle(g: ArmGeometry)=solveGrooveAngle(g,g.outerTargetRadius)
    fun endAngle(g: ArmGeometry)=solveGrooveAngle(g,g.innerTargetRadius)

    fun progressToAngle(positionMs: Long,durationMs: Long,startAngle: Float,endAngle: Float): Float {
        if(durationMs<=0L) return startAngle
        val p=(positionMs.toDouble()/durationMs.toDouble()).coerceIn(0.0,1.0)
        return startAngle+(endAngle-startAngle)*p.toFloat()
    }
    fun trackingAngle(positionMs: Long,durationMs: Long,startAngle: Float,endAngle: Float)=
        progressToAngle(positionMs,durationMs,startAngle,endAngle)
    fun speedRamp(progress: Float,accelerating: Boolean): Float {
        val t=progress.coerceIn(0f,1f); val eased=t*t*(3f-2f*t)
        return if(accelerating)eased else 1f-eased
    }
    fun deckStateFor(shouldPlay: Boolean,currentSpeed: Float): DeckState=when {
        shouldPlay && currentSpeed.coerceIn(0f,1f)>=.999f -> DeckState.PLAYING
        shouldPlay -> DeckState.SPINNING_UP
        currentSpeed.coerceIn(0f,1f)<=.001f -> DeckState.STOPPED
        else -> DeckState.SPINNING_DOWN
    }
    fun blend(current: Float,target: Float,fraction: Float): Float {
        val t=fraction.coerceIn(0f,1f); return current+(target-current)*t
    }
}
