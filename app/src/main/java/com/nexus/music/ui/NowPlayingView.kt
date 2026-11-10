package com.nexus.music.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.nexus.music.playback.PlaybackSnapshot

/** Full-screen Now Playing presentation. All transport commands are delegated to PlaybackConnection. */
class NowPlayingView(
    context: Context,
    private val onClose: () -> Unit,
    private val onToggle: () -> Unit,
    private val onSeek: (Long) -> Unit
) : LinearLayout(context) {
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
    private var tonearmPlaying: Boolean? = null
    private var isSeeking = false
    private var discAnimator: ObjectAnimator? = null

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
        disc.apply {
            setImageResource(R.drawable.vinyl_disc)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Vinyl record"
        }
        stage.addView(disc, FrameLayout.LayoutParams(dp(286), dp(286), Gravity.CENTER))
        tonearm.apply {
            setImageResource(R.drawable.tonearm)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Tonearm"
            // The source PNG's pivot bearing is at (225, 50) in a 276x348 image.
            // At 0.8x scale this places the real pivot outside the record's rim.
            pivotX = dp(180).toFloat()
            pivotY = dp(40).toFloat()
            translationX = dp(63).toFloat()
        }
        stage.addView(tonearm, FrameLayout.LayoutParams(dp(221), dp(278), Gravity.CENTER))
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
        // Skip commands are deliberately not added here: this screen only exposes supported player controls.
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

    private var lastPositionMs = 0L

    fun render(snapshot: PlaybackSnapshot) {
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
        animateTonearmTo(playing)
        if (playing) startDisc() else stopDisc()
    }

    /** Album artwork is intentionally not shown on the record in this player design. */
    @Suppress("UNUSED_PARAMETER")
    fun setArtwork(url: String?) = Unit

    private fun animateTonearmTo(playing: Boolean) {
        if (tonearmPlaying == playing) return
        tonearmPlaying = playing
        tonearm.animate().cancel()
        tonearm.animate()
            .rotation(if (playing) 0f else -42f)
            .translationY(if (playing) 0f else -dp(3).toFloat())
            .setDuration(480L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .start()
    }

    private fun startDisc() {
        if (discAnimator?.isRunning == true) return
        discAnimator?.cancel()
        discAnimator = ObjectAnimator.ofFloat(disc, View.ROTATION, disc.rotation, disc.rotation + 360f).apply {
            duration = 3600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopDisc() {
        discAnimator?.cancel()
        discAnimator = null
    }

    override fun onDetachedFromWindow() {
        stopDisc()
        super.onDetachedFromWindow()
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
}
