package com.nexus.music.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import com.nexus.music.R
import com.nexus.music.playback.PlaybackSnapshot
import kotlin.math.PI
import kotlin.math.sin

/** Full-screen Now Playing presentation. All transport commands are delegated to PlaybackConnection. */
class NowPlayingView(
    context: Context,
    private val onClose: () -> Unit,
    private val onToggle: () -> Unit,
    private val onSeek: (Long) -> Unit
) : LinearLayout(context) {
    private val vinylDiscResource = R.drawable.vinyl_disc
    private val tonearmResource = R.drawable.tonearm

    private val ink = Color.rgb(38, 33, 30)
    private val paper = Color.rgb(241, 232, 214)
    private val muted = Color.rgb(112, 99, 84)
    private val green = Color.rgb(32, 82, 71)
    private val copper = Color.rgb(194, 93, 64)
    private val disc = ImageView(context)
    private val tonearm = ImageView(context)
    private val title = TextView(context)
    private val artist = TextView(context)
    private val elapsed = TextView(context)
    private val remaining = TextView(context)
    private val seek = SeekBar(context)
    private val play = ImageButton(context)
    private var durationMs = 0L
    private var isSeeking = false
    private var lastPositionMs = 0L
    private var lastSnapshotPlaying: Boolean? = null
    private var lastSnapshot: PlaybackSnapshot? = null
    private var discAnimator: ValueAnimator? = null
    private var speedAnimator: ValueAnimator? = null
    private var armAnimator: ValueAnimator? = null
    private var currentSpeed = 0f
    private var armAngle = REST_ANGLE
    private var armLift = 0f
    private var discAngle = 0f
    private var lastFrameTime = 0L
    private var deckState = VinylMotion.DeckState.STOPPED
    private var armPhase = VinylMotion.ArmPhase.RESTING
    private val liftDistancePx = dp(LIFT_HEIGHT_DP)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(16), dp(24), dp(24))
        setBackgroundColor(paper)

        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = button("⌄", 28f, ink).apply {
            contentDescription = "Close Now Playing"
            setOnClickListener { onClose() }
        }
        top.addView(close, LayoutParams(dp(52), dp(48)))
        val label = text("NOW PLAYING", 11f, green, true).apply { letterSpacing = .18f }
        top.addView(label, LayoutParams(0, -2, 1f))
        top.addView(text("N° 01", 10f, muted, true))
        addView(top, LayoutParams(-1, dp(52)))

        val stage = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        stage.addView(RecordShadowView(context), FrameLayout.LayoutParams(dp(DISC_SIZE_DP), dp(DISC_SIZE_DP), Gravity.CENTER))
        disc.apply {
            setImageResource(vinylDiscResource)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Vinyl record"
        }
        stage.addView(disc, FrameLayout.LayoutParams(dp(DISC_SIZE_DP), dp(DISC_SIZE_DP), Gravity.CENTER))
        stage.addView(RecordSheenView(context), FrameLayout.LayoutParams(dp(DISC_SIZE_DP), dp(DISC_SIZE_DP), Gravity.CENTER))
        tonearm.apply {
            setImageResource(tonearmResource)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Tonearm"
            pivotX = dp(TONEARM_PIVOT_X_DP).toFloat()
            pivotY = dp(TONEARM_PIVOT_Y_DP).toFloat()
            translationX = dp(TONEARM_TRANSLATION_X_DP).toFloat()
            rotation = REST_ANGLE
        }
        stage.addView(tonearm, FrameLayout.LayoutParams(dp(TONEARM_WIDTH_DP), dp(TONEARM_HEIGHT_DP), Gravity.CENTER))
        addView(stage, LayoutParams(dp(320), 0, 1f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        title.apply {
            textSize = 27f
            setTextColor(ink)
            typeface = Typeface.create("serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
        }
        addView(title, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        artist.apply {
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        addView(artist, LayoutParams(-1, -2).apply { topMargin = dp(5) })

        val timeline = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        elapsed.apply { textSize = 11f; setTextColor(muted); typeface = Typeface.MONOSPACE }
        remaining.apply { textSize = 11f; setTextColor(muted); typeface = Typeface.MONOSPACE; gravity = Gravity.END }
        timeline.addView(elapsed, LayoutParams(dp(48), -2))
        seek.max = 1000
        seek.progressTintList = android.content.res.ColorStateList.valueOf(green)
        seek.thumbTintList = android.content.res.ColorStateList.valueOf(copper)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) isSeeking = true }
            override fun onStartTrackingTouch(bar: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                if (durationMs > 0L) onSeek(durationMs * (bar?.progress ?: 0) / 1000L)
                isSeeking = false
            }
        })
        timeline.addView(seek, LayoutParams(0, dp(42), 1f))
        timeline.addView(remaining, LayoutParams(dp(48), -2))
        addView(timeline, LayoutParams(-1, dp(48)).apply { topMargin = dp(14) })

        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        val back = button("−15", 14f, ink).apply { contentDescription = "Seek backward 15 seconds" }
        controls.addView(back, LayoutParams(dp(64), dp(64)))
        play.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(green)
            }
            setImageDrawable(TransportGlyph(context, false))
            contentDescription = "Play"
            setOnClickListener { onToggle() }
        }
        controls.addView(play, LayoutParams(dp(76), dp(76)).apply {
            marginStart = dp(18); marginEnd = dp(18)
        })
        val mark = button("N", 18f, copper).apply { contentDescription = "Nexus" }
        controls.addView(mark, LayoutParams(dp(64), dp(64)))
        addView(controls, LayoutParams(-1, dp(88)).apply { topMargin = dp(10) })
        back.setOnClickListener {
            if (durationMs > 0L) onSeek((lastPositionMs - 15_000L).coerceAtLeast(0L))
        }
    }

    fun render(snapshot: PlaybackSnapshot) {
        lastSnapshot = snapshot
        title.text = snapshot.title.ifBlank { "Nothing playing" }
        artist.text = snapshot.artist.ifBlank { "Choose a record from the collection" }
        durationMs = snapshot.durationMs
        lastPositionMs = snapshot.positionMs
        elapsed.text = clock(snapshot.positionMs)
        remaining.text = if (durationMs > 0L) "−${clock((durationMs - snapshot.positionMs).coerceAtLeast(0L))}" else "—:—"
        if (!isSeeking) seek.progress = if (durationMs > 0L) ((snapshot.positionMs * 1000L) / durationMs).toInt().coerceIn(0, 1000) else 0
        seek.isEnabled = durationMs > 0L
        play.setImageDrawable(TransportGlyph(context, snapshot.playWhenReady))
        play.contentDescription = if (snapshot.playWhenReady) "Pause" else "Play"

        val playing = snapshot.title.isNotBlank() && snapshot.playWhenReady
        when {
            lastSnapshotPlaying == null -> {
                lastSnapshotPlaying = playing
                if (playing) beginPlay(snapshot.positionMs, snapshot.durationMs)
            }
            playing && lastSnapshotPlaying != true -> {
                lastSnapshotPlaying = true
                beginPlay(snapshot.positionMs, snapshot.durationMs)
            }
            !playing && lastSnapshotPlaying == true -> {
                lastSnapshotPlaying = false
                beginPause()
            }
            playing -> trackArm(snapshot.positionMs, snapshot.durationMs)
        }
    }

    /** Album artwork is intentionally not shown on the record in this player design. */
    @Suppress("UNUSED_PARAMETER")
    fun setArtwork(url: String?) = Unit

    private fun beginPlay(positionMs: Long, duration: Long) {
        if (!animationsEnabled()) {
            currentSpeed = 1f
            deckState = VinylMotion.DeckState.PLAYING
            armAngle = VinylMotion.trackingAngle(positionMs, duration, PLAY_START_ANGLE, PLAY_END_ANGLE)
            armLift = 0f
            armPhase = VinylMotion.ArmPhase.TRACKING
            disc.rotation = discAngle
            applyArmFrame()
            refreshLayers()
            return
        }
        animateSpeedTo(1f)
        cancelArmAnimation()
        armPhase = VinylMotion.ArmPhase.LIFTING
        animateArmValue(armLift, 1f, ARM_LIFT_MS) {
            val target = VinylMotion.trackingAngle(positionMs, duration, PLAY_START_ANGLE, PLAY_END_ANGLE)
            armPhase = VinylMotion.ArmPhase.SWINGING_TO_GROOVE
            animateArmAngle(target, ARM_SWING_MS) {
                armPhase = VinylMotion.ArmPhase.LOWERING
                lateinit var settle: ValueAnimator
                settle = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = ARM_SETTLE_MS
                    interpolator = DecelerateInterpolator(1.4f)
                    addUpdateListener { animator ->
                        val fraction = animator.animatedValue as Float
                        armAngle = target + sin(PI * fraction).toFloat() * ARM_OVERSHOOT_DEGREES
                        armLift = 1f - fraction
                        applyArmFrame()
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (armAnimator !== settle) return
                            armAngle = target
                            armLift = 0f
                            applyArmFrame()
                            armPhase = VinylMotion.ArmPhase.TRACKING
                            refreshLayers()
                        }
                    })
                }
                armAnimator = settle
                settle.start()
            }
        }
    }

    private fun beginPause() {
        if (!animationsEnabled()) {
            currentSpeed = 0f
            deckState = VinylMotion.DeckState.STOPPED
            armAngle = REST_ANGLE
            armLift = 0f
            armPhase = VinylMotion.ArmPhase.RESTING
            disc.rotation = discAngle
            applyArmFrame()
            stopFrameTicker()
            refreshLayers()
            return
        }
        animateSpeedTo(0f)
        cancelArmAnimation()
        armPhase = VinylMotion.ArmPhase.LIFTING
        animateArmValue(armLift, 1f, ARM_LIFT_MS) {
            armPhase = VinylMotion.ArmPhase.SWINGING_TO_REST
            animateArmAngle(REST_ANGLE, ARM_SWING_MS) {
                armPhase = VinylMotion.ArmPhase.RESTING
                armLift = 0f
                applyArmFrame()
                refreshLayers()
            }
        }
    }

    private fun trackArm(positionMs: Long, duration: Long) {
        if (armLift > 0.01f || armPhase == VinylMotion.ArmPhase.SWINGING_TO_GROOVE ||
            armPhase == VinylMotion.ArmPhase.LOWERING || armPhase == VinylMotion.ArmPhase.LIFTING
        ) return
        val target = VinylMotion.trackingAngle(positionMs, duration, PLAY_START_ANGLE, PLAY_END_ANGLE)
        if (kotlin.math.abs(target - armAngle) < TRACKING_EPSILON_DEGREES) return
        if (!animationsEnabled()) {
            armAngle = target
            applyArmFrame()
            return
        }
        armPhase = VinylMotion.ArmPhase.TRACKING
        animateArmAngle(target, SEEK_GLIDE_MS) { armPhase = VinylMotion.ArmPhase.TRACKING }
    }

    private fun animateSpeedTo(target: Float) {
        val previousAnimator = speedAnimator
        speedAnimator = null
        previousAnimator?.cancel()
        val start = currentSpeed
        if (kotlin.math.abs(target - start) < 0.001f) {
            currentSpeed = target
            deckState = if (target > 0f) VinylMotion.DeckState.PLAYING else VinylMotion.DeckState.STOPPED
            if (target > 0f) startFrameTicker() else stopFrameTicker()
            refreshLayers()
            return
        }
        val accelerating = target > start
        deckState = VinylMotion.deckStateFor(target > 0f, currentSpeed)
        if (target > 0f) startFrameTicker()
        val duration = if (accelerating) SPIN_UP_MS else SPIN_DOWN_MS
        lateinit var animator: ValueAnimator
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                currentSpeed = VinylMotion.blend(start, target, VinylMotion.speedRamp(animation.animatedValue as Float, accelerating))
                deckState = when {
                    target > 0f && currentSpeed >= 0.999f -> VinylMotion.DeckState.PLAYING
                    target > 0f -> VinylMotion.DeckState.SPINNING_UP
                    currentSpeed > 0.001f -> VinylMotion.DeckState.SPINNING_DOWN
                    else -> VinylMotion.DeckState.STOPPED
                }
                if (currentSpeed <= 0.001f && target == 0f) {
                    currentSpeed = 0f
                    deckState = VinylMotion.DeckState.STOPPED
                    stopFrameTicker()
                }
                refreshLayers()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (speedAnimator !== animator) return
                    currentSpeed = target
                    deckState = if (target > 0f) VinylMotion.DeckState.PLAYING else VinylMotion.DeckState.STOPPED
                    if (target == 0f) stopFrameTicker()
                    refreshLayers()
                }
            })
        }
        speedAnimator = animator
        animator.start()
    }

    private fun animateArmValue(from: Float, to: Float, durationMs: Long, end: () -> Unit) {
        lateinit var animator: ValueAnimator
        animator = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                armLift = it.animatedValue as Float
                applyArmFrame()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (armAnimator !== animator) return
                    armLift = to
                    applyArmFrame()
                    end()
                }
            })
        }
        armAnimator = animator
        animator.start()
    }

    private fun animateArmAngle(target: Float, durationMs: Long, end: () -> Unit) {
        val previousAnimator = armAnimator
        armAnimator = null
        previousAnimator?.cancel()
        val from = armAngle
        lateinit var animator: ValueAnimator
        animator = ValueAnimator.ofFloat(from, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.35f)
            addUpdateListener {
                armAngle = it.animatedValue as Float
                applyArmFrame()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (armAnimator !== animator) return
                    armAngle = target
                    applyArmFrame()
                    end()
                }
            })
        }
        armAnimator = animator
        animator.start()
    }

    private fun applyArmFrame() {
        tonearm.rotation = armAngle + if (armLift < 0.05f) {
            sin(SystemClock.uptimeMillis().toDouble() / STYLUS_VIBRATION_PERIOD_MS) .toFloat() * STYLUS_VIBRATION_DEGREES
        } else 0f
        tonearm.translationY = -liftDistancePx * armLift
        tonearm.elevation = dp(1) + dp(ARM_SHADOW_HEIGHT_DP) * armLift
    }

    private fun startFrameTicker() {
        if (discAnimator?.isStarted == true) return
        lastFrameTime = SystemClock.uptimeMillis()
        val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FRAME_TICK_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = SystemClock.uptimeMillis()
                val delta = (now - lastFrameTime).coerceAtLeast(0L)
                lastFrameTime = now
                discAngle = (discAngle + delta * DEGREES_PER_MS * currentSpeed) % 360f
                val wobble = sin(now.toDouble() / PLATTER_WOBBLE_PERIOD_MS).toFloat() * PLATTER_WOBBLE_DEGREES
                disc.rotation = discAngle + wobble
                if (armLift < 0.05f && armPhase == VinylMotion.ArmPhase.TRACKING) {
                    tonearm.rotation = armAngle + sin(now.toDouble() / STYLUS_VIBRATION_PERIOD_MS).toFloat() * STYLUS_VIBRATION_DEGREES
                }
            }
        }
        discAnimator = ticker
        disc.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        ticker.start()
    }

    private fun stopFrameTicker() {
        discAnimator?.cancel()
        discAnimator = null
        disc.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    private fun cancelArmAnimation() {
        val previousAnimator = armAnimator
        armAnimator = null
        previousAnimator?.cancel()
    }

    private fun stopAllMotion() {
        discAnimator?.cancel()
        discAnimator = null
        val previousSpeedAnimator = speedAnimator
        speedAnimator = null
        previousSpeedAnimator?.cancel()
        val previousArmAnimator = armAnimator
        armAnimator = null
        previousArmAnimator?.cancel()
        disc.setLayerType(View.LAYER_TYPE_NONE, null)
        tonearm.setLayerType(View.LAYER_TYPE_NONE, null)
        lastFrameTime = 0L
    }

    private fun refreshLayers() {
        val animating = currentSpeed > 0.001f || speedAnimator?.isRunning == true || armAnimator?.isRunning == true
        val layer = if (animating) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        disc.setLayerType(layer, null)
        tonearm.setLayerType(layer, null)
    }

    private fun animationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView !== this) return
        if (visibility != VISIBLE) {
            stopAllMotion()
        } else {
            resumeMotionIfNeeded()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resumeMotionIfNeeded()
    }

    override fun onDetachedFromWindow() {
        stopAllMotion()
        super.onDetachedFromWindow()
    }

    private fun resumeMotionIfNeeded() {
        val snapshot = lastSnapshot ?: return
        if (!isShown || visibility != VISIBLE) return
        val playing = snapshot.title.isNotBlank() && snapshot.playWhenReady
        if (playing) {
            currentSpeed = currentSpeed.coerceIn(0f, 1f)
            beginPlay(snapshot.positionMs, snapshot.durationMs)
        } else {
            currentSpeed = 0f
            armAngle = REST_ANGLE
            armLift = 0f
            deckState = VinylMotion.DeckState.STOPPED
            armPhase = VinylMotion.ArmPhase.RESTING
            applyArmFrame()
        }
    }

    private fun button(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        isClickable = true; isFocusable = true
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun clock(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) / 1000L)
        return String.format("%d:%02d", seconds / 60L, seconds % 60L)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class RecordShadowView(context: Context) : View(context) {
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadowBounds = RectF()
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            shadowBounds.set(w * 0.06f, h * 0.12f, w * 0.94f, h * 0.90f)
            shadowPaint.shader = RadialGradient(w / 2f, h * 0.52f, w * 0.48f,
                intArrayOf(0x26000000, 0x12000000, Color.TRANSPARENT), floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawOval(shadowBounds, shadowPaint)
        }
    }

    private inner class RecordSheenView(context: Context) : View(context) {
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x22FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val fineSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x12FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }
        private val bounds = RectF()
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            bounds.set(w * 0.08f, h * 0.08f, w * 0.92f, h * 0.92f)
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawArc(bounds, 205f, 82f, false, sheenPaint)
            canvas.drawArc(bounds, 28f, 54f, false, fineSheenPaint)
        }
    }

    private class TransportGlyph(context: Context, private val playing: Boolean) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        override fun draw(canvas: Canvas) {
            val b = bounds; val s = minOf(b.width(), b.height()) * .38f
            val l = b.centerX() - s / 2f; val t = b.centerY() - s / 2f
            if (playing) {
                canvas.drawRect(l, t, l + s * .3f, t + s, paint)
                canvas.drawRect(l + s * .65f, t, l + s, t + s, paint)
            } else {
                val path = Path().apply { moveTo(l, t); lineTo(l + s, b.centerY().toFloat()); lineTo(l, t + s); close() }
                canvas.drawPath(path, paint)
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android graphics API") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val DISC_SIZE_DP = 286
        const val TONEARM_WIDTH_DP = 221
        const val TONEARM_HEIGHT_DP = 278
        const val TONEARM_PIVOT_X_DP = 180
        const val TONEARM_PIVOT_Y_DP = 40
        const val TONEARM_TRANSLATION_X_DP = 63
        const val REST_ANGLE = -42f
        const val PLAY_START_ANGLE = 0f
        const val PLAY_END_ANGLE = 4.5f
        const val LIFT_HEIGHT_DP = 4
        const val ARM_SHADOW_HEIGHT_DP = 6
        const val ARM_LIFT_MS = 150L
        const val ARM_SWING_MS = 800L
        const val ARM_SETTLE_MS = 250L
        const val ARM_OVERSHOOT_DEGREES = 2.5f
        const val SPIN_UP_MS = 1_200L
        const val SPIN_DOWN_MS = 1_800L
        const val DEGREES_PER_MS = 360f / 1_800f
        const val FRAME_TICK_MS = 16L
        const val SEEK_GLIDE_MS = 300L
        const val TRACKING_EPSILON_DEGREES = 0.08f
        const val PLATTER_WOBBLE_PERIOD_MS = 6_000L
        const val PLATTER_WOBBLE_DEGREES = 0.22f
        const val STYLUS_VIBRATION_PERIOD_MS = 145L
        const val STYLUS_VIBRATION_DEGREES = 0.10f
    }
}
