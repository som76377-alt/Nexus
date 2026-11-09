package com.nexus.music

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import coil3.load
import com.nexus.music.playback.PlaybackConnection
import com.nexus.music.playback.PlaybackSnapshot
import com.nexus.music.ui.Drawables
import com.nexus.music.ui.NexusUi
import com.nexus.music.ui.Theme
import com.nexus.music.ui.Type

class NowPlayingActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var playback: PlaybackConnection? = null
    private var started = false
    private var seeking = false
    private var discAnimator: ObjectAnimator? = null
    private var tonearmAnimator: ObjectAnimator? = null
    private lateinit var discLayer: FrameLayout
    private lateinit var discArtwork: ImageView
    private lateinit var tonearm: TonearmView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var position: TextView
    private lateinit var duration: TextView
    private lateinit var seek: SeekBar
    private lateinit var playPause: ImageButton
    private lateinit var emptyState: TextView
    private lateinit var vinylSurface: VinylSurfaceView
    private var currentTab = "Related"
    private var lastTonearmPlaying: Boolean? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!started || isFinishing || isDestroyed) return
            playback?.let { render(it.snapshot()) }
            handler.postDelayed(this, 400L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.ColorToken.parchment
        window.navigationBarColor = Theme.ColorToken.parchment
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.ColorToken.parchment)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(28))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            gravity = Gravity.CENTER
            contentDescription = "Close player"
            minWidth = dp(48)
            minHeight = dp(48)
            setTextColor(Theme.ColorToken.ink)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(NexusUi.text(this, "NOW PLAYING", 11f, Theme.ColorToken.mineral).apply {
            letterSpacing = 0.12f
            typeface = Type.medium(this@NowPlayingActivity)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(header)

        val diameter = minOf((resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt() - 72, 300)
            .coerceAtLeast(220)
        val discPx = dp(diameter)
        val labelPx = (discPx * 0.38f).toInt()
        val discStack = FrameLayout(this).apply {
            contentDescription = "Vinyl record player artwork. Swipe down to close."
        }
        discLayer = FrameLayout(this)
        vinylSurface = VinylSurfaceView(this, discPx)
        discLayer.addView(vinylSurface, FrameLayout.LayoutParams(-1, -1))
        discArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Theme.ColorToken.groove)
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            contentDescription = "Album artwork on the record label"
        }
        discLayer.addView(discArtwork, FrameLayout.LayoutParams(labelPx, labelPx, Gravity.CENTER))
        discStack.addView(discLayer, FrameLayout.LayoutParams(discPx, discPx, Gravity.CENTER))
        discStack.addView(SheenView(this), FrameLayout.LayoutParams(discPx, discPx, Gravity.CENTER))
        tonearm = TonearmView(this).apply {
            pivotX = dp(16).toFloat()
            pivotY = dp(16).toFloat()
            rotation = -18f
        }
        discStack.addView(tonearm, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(4)
            marginEnd = dp(2)
        })
        content.addView(discStack, LinearLayout.LayoutParams(-1, discPx).apply {
            topMargin = dp(16)
            bottomMargin = dp(22)
        })
        var downY = 0f
        discStack.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = event.rawY; true }
                MotionEvent.ACTION_UP -> {
                    if (event.rawY - downY > dp(72)) finish()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }

        title = NexusUi.text(this, "Nothing playing", 22f, Theme.ColorToken.ink, bold = true, maxLines = 2).apply {
            typeface = Type.title(this@NowPlayingActivity)
            gravity = Gravity.CENTER
        }
        artist = NexusUi.text(this, "Choose a track from Home", 14f, Theme.ColorToken.ink2, maxLines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        content.addView(title)
        content.addView(artist)

        val seekLabels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        position = NexusUi.text(this, "0:00", 11f, Theme.ColorToken.ink2)
        duration = NexusUi.text(this, "0:00", 11f, Theme.ColorToken.ink2)
        seekLabels.addView(position, LinearLayout.LayoutParams(0, -2, 1f))
        seekLabels.addView(duration)
        content.addView(seekLabels, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(22)
        })
        seek = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Theme.ColorToken.copper)
            thumbTintList = ColorStateList.valueOf(Theme.ColorToken.copper)
            progressBackgroundTintList = ColorStateList.valueOf(Theme.ColorToken.ink3)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) seeking = true
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val snapshot = playback?.snapshot() ?: return
                    if (snapshot.durationMs > 0L) {
                        playback?.seekTo(snapshot.durationMs * (bar?.progress ?: 0) / 1000L)
                    }
                    seeking = false
                }
            })
        }
        content.addView(seek, LinearLayout.LayoutParams(-1, dp(32)))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        val previous = transportButton(TransportGlyph.PREVIOUS, "Restart current track") {
            playback?.seekTo(0L)
        }
        val next = transportButton(TransportGlyph.NEXT, "Next track unavailable until a queue exists") { }
        next.isEnabled = false
        playPause = ImageButton(this).apply {
            contentDescription = "Play"
            background = Drawables.copperButton(this@NowPlayingActivity)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setImageDrawable(TransportGlyphDrawable(this@NowPlayingActivity, TransportGlyph.PLAY))
            setOnClickListener { playback?.togglePlayback() }
        }
        controls.addView(previous, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(26) })
        controls.addView(playPause, LinearLayout.LayoutParams(dp(56), dp(56)))
        controls.addView(next, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(26) })
        content.addView(controls)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(actionText("Like") {
            Toast.makeText(this@NowPlayingActivity, "Library actions are being connected.", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(actionText("Add to playlist") {
            Toast.makeText(this@NowPlayingActivity, "Playlist actions arrive with Library.", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(actions)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, dp(10))
        }
        val related = NexusUi.actionButton(this, "Related", true)
        val upNext = NexusUi.actionButton(this, "Up next", false)
        related.setOnClickListener { showTab("Related", related, upNext) }
        upNext.setOnClickListener { showTab("Up next", upNext, related) }
        tabs.addView(related, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        tabs.addView(upNext, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        content.addView(tabs)
        emptyState = NexusUi.text(this, "Related tracks will appear here when listening history is available.", 13f, Theme.ColorToken.ink2).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(22), dp(16), dp(22))
            background = Drawables.paperCard(this@NowPlayingActivity)
        }
        content.addView(emptyState, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)

        playback = PlaybackConnection(
            context = this,
            onReady = { render(playback?.snapshot() ?: PlaybackSnapshot(connected = false)) },
            onStateChanged = ::render,
            onError = { message -> Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        )
    }

    private fun showTab(tab: String, selected: Button, other: Button) {
        currentTab = tab
        selected.background = Drawables.rounded(this, Theme.ColorToken.mineral, 14f)
        selected.setTextColor(Theme.ColorToken.paperStock)
        other.background = Drawables.rounded(this, Theme.ColorToken.paperStock, 14f)
        other.setTextColor(Theme.ColorToken.ink)
        emptyState.text = if (tab == "Related") {
            "Related tracks will appear here when listening history is available."
        } else {
            "Your queue is empty. Add tracks to play them next."
        }
    }

    private fun actionText(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        typeface = Type.medium(this@NowPlayingActivity)
        setTextColor(Theme.ColorToken.mineral)
        minHeight = dp(48)
        contentDescription = label
        setOnClickListener { onClick() }
    }

    private fun transportButton(glyph: TransportGlyph, description: String, onClick: () -> Unit) =
        ImageButton(this).apply {
            contentDescription = description
            background = Drawables.rounded(this@NowPlayingActivity, Color.TRANSPARENT, 24f)
            setImageDrawable(TransportGlyphDrawable(this@NowPlayingActivity, glyph))
            setOnClickListener { onClick() }
        }

    private fun render(snapshot: PlaybackSnapshot) {
        if (!snapshot.connected || snapshot.title.isBlank()) {
            title.text = "Nothing playing"
            artist.text = "Choose a track from Home"
            playPause.isEnabled = false
            playPause.contentDescription = "Play"
            return
        }
        title.text = snapshot.title
        artist.text = snapshot.artist.ifBlank { "Unknown artist" }
        playPause.isEnabled = true
        playPause.contentDescription = if (snapshot.isPlaying) "Pause" else "Play"
        playPause.setImageDrawable(TransportGlyphDrawable(this, if (snapshot.isPlaying) TransportGlyph.PAUSE else TransportGlyph.PLAY))
        if (!seeking && snapshot.durationMs > 0L) {
            seek.progress = ((snapshot.positionMs * 1000L) / snapshot.durationMs).toInt().coerceIn(0, 1000)
        }
        position.text = formatTime(snapshot.positionMs)
        duration.text = formatTime(snapshot.durationMs)
        syncDisc(snapshot)
    }

    private fun syncDisc(snapshot: PlaybackSnapshot) {
        if (snapshot.title.isNotBlank()) {
            val artUri = getSharedPreferences("nexus_home", MODE_PRIVATE)
                .getString("last_track_thumbnail", null)?.takeIf { it.isNotBlank() }
            if (artUri != null && discArtwork.tag != artUri) {
                discArtwork.tag = artUri
                discArtwork.load(artUri)
            }
        }
        if (started && snapshot.isPlaying) {
            if (discAnimator?.isStarted != true) {
                discAnimator?.cancel()
                discAnimator = ObjectAnimator.ofFloat(
                    discLayer, View.ROTATION, discLayer.rotation, discLayer.rotation + 360f
                ).apply {
                    duration = 3_600L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                }
            }
        } else {
            discAnimator?.cancel()
            discAnimator = null
        }
        if (lastTonearmPlaying != snapshot.isPlaying) {
            lastTonearmPlaying = snapshot.isPlaying
            tonearmAnimator?.cancel()
            if (started) {
                tonearmAnimator = ObjectAnimator.ofFloat(tonearm, View.ROTATION,
                    tonearm.rotation, if (snapshot.isPlaying) 0f else -18f
                ).apply {
                    duration = 350L
                    interpolator = DecelerateInterpolator()
                    start()
                }
            } else {
                tonearm.rotation = if (snapshot.isPlaying) 0f else -18f
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) / 1000L)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun stopAnimations() {
        discAnimator?.cancel()
        discAnimator = null
        tonearmAnimator?.cancel()
        tonearmAnimator = null
    }

    override fun onStart() {
        super.onStart()
        started = true
        startTicker()
        playback?.let { render(it.snapshot()) }
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(ticker)
        stopAnimations()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        stopAnimations()
        playback?.close()
        playback = null
        vinylSurface.release()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class TransportGlyph { PLAY, PAUSE, PREVIOUS, NEXT }

    private class TransportGlyphDrawable(context: android.content.Context, private val glyph: TransportGlyph) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.ColorToken.ink
            style = Paint.Style.FILL
        }
        private val path = Path()
        override fun draw(canvas: Canvas) {
            val b = bounds
            val size = minOf(b.width(), b.height()).toFloat() * 0.58f
            val left = b.centerX() - size / 2f
            val top = b.centerY() - size / 2f
            path.reset()
            when (glyph) {
                TransportGlyph.PLAY -> {
                    paint.color = Theme.ColorToken.paperStock
                    path.moveTo(left, top); path.lineTo(left + size, top + size / 2f)
                    path.lineTo(left, top + size); path.close()
                    canvas.drawPath(path, paint)
                }
                TransportGlyph.PAUSE -> {
                    paint.color = Theme.ColorToken.paperStock
                    val bar = size * 0.28f
                    canvas.drawRect(left, top, left + bar, top + size, paint)
                    canvas.drawRect(left + size - bar, top, left + size, top + size, paint)
                }
                TransportGlyph.PREVIOUS -> {
                    paint.color = Theme.ColorToken.ink
                    canvas.drawRect(left, top, left + size * 0.12f, top + size, paint)
                    path.moveTo(left + size * 0.2f, top)
                    path.lineTo(left + size * 0.78f, top + size / 2f)
                    path.lineTo(left + size * 0.2f, top + size); path.close()
                    canvas.drawPath(path, paint)
                }
                TransportGlyph.NEXT -> {
                    paint.color = Theme.ColorToken.ink
                    canvas.drawRect(left + size * 0.88f, top, left + size, top + size, paint)
                    path.moveTo(left + size * 0.8f, top)
                    path.lineTo(left + size * 0.22f, top + size / 2f)
                    path.lineTo(left + size * 0.8f, top + size); path.close()
                    canvas.drawPath(path, paint)
                }
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android graphics API")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = (24 * density).toInt()
        override fun getIntrinsicHeight(): Int = (24 * density).toInt()
    }

    private class VinylSurfaceView(context: android.content.Context, size: Int) : View(context) {
        private val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        private val source = Rect(0, 0, size, size)
        private val target = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        init {
            val canvas = Canvas(bitmap)
            val center = size / 2f
            val radius = size / 2f
            val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ColorToken.recordBlack; style = Paint.Style.FILL }
            canvas.drawCircle(center, center, radius, base)
            val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = maxOf(1f, size / 520f)
            }
            for (i in 0 until 48) {
                val r = radius * 0.43f + i * (radius * 0.55f / 48f)
                groove.color = if (i % 2 == 0) 0xFF29231D.toInt() else 0xFF0F0C09.toInt()
                canvas.drawCircle(center, center, r, groove)
            }
            val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ColorToken.groove }
            canvas.drawCircle(center, center, radius * 0.18f, hub)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            target.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(bitmap, source, target, paint)
        }
        fun release() { if (!bitmap.isRecycled) bitmap.recycle() }
    }

    private class SheenView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(20, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 2f
        }
        private val arc = RectF()
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val inset = w * 0.09f
            arc.set(inset, inset, w - inset, h - inset)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawArc(arc, 210f, 62f, false, paint)
        }
    }

    private class TonearmView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.ColorToken.ink2
            strokeWidth = context.resources.displayMetrics.density * 4f
            strokeCap = Paint.Cap.ROUND
        }
        private val fine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.ColorToken.copper
            style = Paint.Style.FILL
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawLine(dp(context, 16).toFloat(), dp(context, 16).toFloat(),
                width * 0.84f, height * 0.84f, paint)
            canvas.drawCircle(dp(context, 16).toFloat(), dp(context, 16).toFloat(),
                dp(context, 7).toFloat(), fine)
            canvas.drawCircle(width * 0.84f, height * 0.84f, dp(context, 4).toFloat(), fine)
        }
        private fun dp(context: android.content.Context, value: Int) =
            (value * context.resources.displayMetrics.density).toInt()
    }
}
