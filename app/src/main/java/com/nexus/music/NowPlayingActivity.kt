package com.nexus.music

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
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
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import coil3.load
import com.nexus.music.library.LibraryTrack
import com.nexus.music.library.PlaybackHistoryTracker
import com.nexus.music.library.SqliteLibraryRepository
import com.nexus.music.library.currentCatalogTrack
import com.nexus.music.playback.PlaybackConnection
import com.nexus.music.playback.PlaybackSnapshot
import com.nexus.music.ui.Drawables
import com.nexus.music.ui.NexusUi
import com.nexus.music.ui.Motion
import com.nexus.music.ui.Theme
import com.nexus.music.ui.Type
import com.nexus.music.ui.PaperTexture

class NowPlayingActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var playback: PlaybackConnection? = null
    private val libraryRepositoryDelegate = lazy { SqliteLibraryRepository(applicationContext) }
    private val libraryRepository by libraryRepositoryDelegate
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
    private lateinit var likeAction: TextView
    private lateinit var playlistAction: TextView
    private var displayedTrackId: String? = null
    private var hasRenderedTrackActions = false
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
        PaperTexture.prepare(this, PaperTexture.Material.PLAYER_SLEEVE) { texture ->
            if (texture != null && !isDestroyed) root.background = texture
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
        // The reflection belongs to the record surface, so it rotates with the grooves.
        discLayer.addView(SheenView(this), FrameLayout.LayoutParams(discPx, discPx, Gravity.CENTER))
        discStack.addView(discLayer, FrameLayout.LayoutParams(discPx, discPx, Gravity.CENTER))
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
            setOnClickListener { Motion.playHaptic(this); playback?.togglePlayback() }
        }
        controls.addView(previous, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(26) })
        controls.addView(playPause, LinearLayout.LayoutParams(dp(56), dp(56)))
        controls.addView(next, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(26) })
        content.addView(controls)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        likeAction = actionText("Like") { toggleLike() }
        playlistAction = actionText("Add to playlist") { showAddToPlaylist() }
        likeAction.isEnabled = false
        playlistAction.isEnabled = false
        actions.addView(likeAction, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(playlistAction, LinearLayout.LayoutParams(0, dp(48), 1f))
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

    private fun updateLibraryActions(snapshot: PlaybackSnapshot) {
        val track = currentCatalogTrack(this, snapshot)
        val videoId = track?.let { LibraryTrack.from(it)?.id }
        if (hasRenderedTrackActions && displayedTrackId == videoId) return
        hasRenderedTrackActions = true
        displayedTrackId = videoId
        val liked = videoId?.let(libraryRepository::isLiked) == true
        likeAction.text = if (liked) "Liked" else "Like"
        likeAction.isEnabled = videoId != null
        playlistAction.isEnabled = videoId != null
        playlistAction.alpha = if (videoId != null) 1f else 0.45f
        likeAction.alpha = if (videoId != null) 1f else 0.45f
    }

    private fun toggleLike() {
        val snapshot = playback?.snapshot() ?: return
        val track = currentCatalogTrack(this, snapshot) ?: run {
            Toast.makeText(this, "This item cannot be saved to your library.", Toast.LENGTH_SHORT).show()
            return
        }
        val videoId = LibraryTrack.from(track)?.id ?: return
        val liked = libraryRepository.isLiked(videoId)
        if (!libraryRepository.setLiked(track, !liked)) {
            Toast.makeText(this, "This item could not be saved.", Toast.LENGTH_SHORT).show()
            return
        }
        likeAction.text = if (liked) "Like" else "Liked"
        displayedTrackId = videoId
        hasRenderedTrackActions = true
        Toast.makeText(this, if (liked) "Removed from Liked" else "Added to Liked", Toast.LENGTH_SHORT).show()
    }

    private fun showAddToPlaylist() {
        val snapshot = playback?.snapshot() ?: return
        val track = currentCatalogTrack(this, snapshot) ?: run {
            Toast.makeText(this, "This item cannot be added to a playlist.", Toast.LENGTH_SHORT).show()
            return
        }
        val playlists = libraryRepository.playlists()
        val options = playlists.map { it.name } + "Create new playlist"
        AlertDialog.Builder(this)
            .setTitle("Add to playlist")
            .setItems(options.toTypedArray()) { _, selected ->
                if (selected == playlists.size) {
                    showCreatePlaylistDialog(track)
                } else {
                    val playlist = playlists[selected]
                    if (libraryRepository.addToPlaylist(playlist.id, track)) {
                        Toast.makeText(this, "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Could not add this track.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreatePlaylistDialog(track: com.nexus.music.catalog.CatalogTrack) {
        val input = EditText(this).apply {
            hint = "Playlist name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Create playlist")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                try {
                    val playlistId = libraryRepository.createPlaylist(input.text.toString())
                    if (libraryRepository.addToPlaylist(playlistId, track)) {
                        Toast.makeText(this, "Playlist created and track added", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Playlist created, but track could not be added.", Toast.LENGTH_LONG).show()
                    }
                } catch (error: Exception) {
                    Toast.makeText(this, error.message ?: "Could not create playlist.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun observeLibraryHistory(snapshot: PlaybackSnapshot) {
        val track = currentCatalogTrack(this, snapshot)
        PlaybackHistoryTracker.shared.observe(
            snapshot = snapshot,
            track = track,
            elapsedRealtimeMillis = SystemClock.elapsedRealtime()
        ) { item ->
            libraryRepository.recordHistory(item, System.currentTimeMillis())
        }
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
        syncDisc(snapshot)
        updateLibraryActions(snapshot)
        if (started) observeLibraryHistory(snapshot)
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
        PlaybackHistoryTracker.shared.suspendObservation()
        handler.removeCallbacks(ticker)
        stopAnimations()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        stopAnimations()
        playback?.close()
        playback = null
        if (libraryRepositoryDelegate.isInitialized()) libraryRepository.close()
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
            val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Theme.ColorToken.recordBlack
                style = Paint.Style.FILL
            }
            canvas.drawCircle(center, center, radius, base)

            // Fine, slightly varied groove spacing reads as a pressed record rather than
            // a stack of identical concentric outlines.
            val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = maxOf(.65f, size / 900f)
            }
            for (i in 0 until 112) {
                val fraction = i / 111f
                val r = radius * (.435f + fraction * .545f)
                groove.color = when (i % 4) {
                    0 -> 0xFF302922.toInt()
                    1 -> 0xFF0D0A08.toInt()
                    2 -> 0xFF211B16.toInt()
                    else -> 0xFF100D0A.toInt()
                }
                groove.strokeWidth = if (i % 8 == 0) maxOf(1f, size / 700f) else maxOf(.55f, size / 1150f)
                canvas.drawCircle(center, center, r, groove)
            }

            val labelBoundary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF332A22.toInt()
                style = Paint.Style.STROKE
                strokeWidth = maxOf(1f, size / 500f)
            }
            canvas.drawCircle(center, center, radius * .218f, labelBoundary)
            val innerRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF0B0907.toInt()
                style = Paint.Style.STROKE
                strokeWidth = maxOf(1f, size / 600f)
            }
            canvas.drawCircle(center, center, radius * .205f, innerRim)

            val spindle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF090807.toInt() }
            canvas.drawCircle(center, center, radius * .012f, spindle)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            target.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(bitmap, source, target, paint)
        }

        fun release() {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private class SheenView(context: android.content.Context) : View(context) {
        private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(18, 255, 244, 223)
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 1.5f
        }
        private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(8, 255, 248, 232)
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * .8f
        }
        private val outer = RectF()
        private val inner = RectF()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val inset = w * .055f
            outer.set(inset, inset, w - inset, h - inset)
            inner.set(inset * 2.1f, inset * 2.1f, w - inset * 2.1f, h - inset * 2.1f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawArc(outer, 208f, 54f, false, outerPaint)
            canvas.drawArc(inner, 215f, 34f, false, innerPaint)
        }
    }

    private class TonearmView(context: android.content.Context) : View(context) {
        private val armShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 14, 12, 10)
            strokeWidth = context.resources.displayMetrics.density * 7f
            strokeCap = Paint.Cap.ROUND
        }
        private val armMetal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(151, 143, 128)
            strokeWidth = context.resources.displayMetrics.density * 4.3f
            strokeCap = Paint.Cap.ROUND
        }
        private val armHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(155, 239, 231, 213)
            strokeWidth = context.resources.displayMetrics.density * .9f
            strokeCap = Paint.Cap.ROUND
        }
        private val pivotDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(48, 42, 34) }
        private val pivotMetal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(177, 166, 145) }
        private val copper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ColorToken.copper }
        private val headshell = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(117, 108, 94)
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = context.resources.displayMetrics.density
            val pivotX = 16f * density
            val pivotY = 16f * density
            val tipX = width * .82f
            val tipY = height * .80f

            canvas.drawLine(pivotX, pivotY, tipX, tipY, armShadow)
            canvas.drawLine(pivotX, pivotY, tipX, tipY, armMetal)
            canvas.drawLine(pivotX, pivotY, tipX, tipY, armHighlight)

            // A layered bearing, counterweight and headshell make the assembly read as
            // a small mechanical tonearm rather than a bare stick.
            canvas.drawCircle(pivotX, pivotY, 9f * density, armShadow)
            canvas.drawCircle(pivotX, pivotY, 7f * density, pivotMetal)
            canvas.drawCircle(pivotX, pivotY, 3.1f * density, pivotDark)
            canvas.drawCircle(pivotX, pivotY, 1.5f * density, copper)

            canvas.save()
            val angle = Math.toDegrees(kotlin.math.atan2((tipY - pivotY).toDouble(), (tipX - pivotX).toDouble())).toFloat()
            canvas.rotate(angle, tipX, tipY)
            val shell = Path().apply {
                moveTo(tipX - 3f * density, tipY - 4f * density)
                lineTo(tipX + 8f * density, tipY - 3f * density)
                lineTo(tipX + 10f * density, tipY + 3f * density)
                lineTo(tipX - 2f * density, tipY + 4f * density)
                close()
            }
            canvas.drawPath(shell, headshell)
            canvas.drawLine(tipX + 7f * density, tipY + 1f * density,
                tipX + 13f * density, tipY + 5f * density, armHighlight)
            canvas.drawCircle(tipX + 13f * density, tipY + 5f * density, 1.7f * density, copper)
            canvas.restore()
        }
    }
}
