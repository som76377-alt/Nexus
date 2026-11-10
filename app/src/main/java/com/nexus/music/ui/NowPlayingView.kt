package com.nexus.music.ui

import com.nexus.music.R

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
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
    private val tonearm = TonearmView(context)
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
    private var tonearmAnimator: ValueAnimator? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(16), dp(24), dp(24))
        clipChildren = false
        clipToPadding = false
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
            setImageDrawable(VinylRecordDrawable())
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "Vinyl record"
            visibility = View.VISIBLE
        }
        stage.addView(disc, FrameLayout.LayoutParams(dp(286), dp(286), Gravity.CENTER))
        stage.addView(VinylLightView(context), FrameLayout.LayoutParams(dp(286), dp(286), Gravity.CENTER))
        tonearm.apply {
            contentDescription = "Tonearm"
            visibility = View.VISIBLE
            // Keep the pivot just beyond the platter rim and the arm artwork inside
            // the stage so its real outward rest movement is never clipped.
            translationX = dp(78).toFloat()
        }
        stage.addView(tonearm, FrameLayout.LayoutParams(dp(232), dp(286), Gravity.CENTER))
        // A fixed stage prevents the artwork area from collapsing during layout.
        addView(stage, LayoutParams(dp(320), dp(320)).apply {
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
        tonearmAnimator?.cancel()
        val targetAngle = if (playing) 0f else -24f
        tonearmAnimator = ValueAnimator.ofFloat(tonearm.angleDegrees, targetAngle).apply {
            duration = 520L
            interpolator = android.view.animation.DecelerateInterpolator(1.4f)
            addUpdateListener { tonearm.angleDegrees = it.animatedValue as Float }
            start()
        }
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
        tonearmAnimator?.cancel()
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


    private class TonearmView(context: Context) : View(context) {
        private val artwork = RealTonearmArtwork()
        var angleDegrees: Float = 0f
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            artwork.draw(canvas, width, height, angleDegrees)
        }
    }

    private class VinylLightView(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val side=minOf(width,height).toFloat(); val x=width/2f; val y=height/2f; val r=side*.485f
            canvas.save()
            canvas.clipPath(Path().apply { addCircle(x,y,r,Path.Direction.CW) })
            val tri=Path().apply {
                moveTo(x-r*.88f,y-r*.52f); lineTo(x-r*.49f,y-r*.84f)
                cubicTo(x-r*.12f,y-r*.72f,x+r*.23f,y-r*.43f,x+r*.46f,y-r*.10f)
                lineTo(x+r*.13f,y-r*.31f); cubicTo(x-r*.18f,y-r*.57f,x-r*.54f,y-r*.65f,x-r*.88f,y-r*.52f); close()
            }
            p.style=Paint.Style.FILL
            p.shader=LinearGradient(x-r*.7f,y-r*.75f,x+r*.35f,y-r*.12f,
                intArrayOf(0x00ffffff,0x12ffffff,0x30ffffff,0x0effffff,0x00ffffff),
                floatArrayOf(0f,.25f,.48f,.76f,1f),Shader.TileMode.CLAMP)
            canvas.drawPath(tri,p); p.shader=null
            val edge=Path().apply { moveTo(x-r*.82f,y-r*.55f); cubicTo(x-r*.45f,y-r*.78f,x-r*.02f,y-r*.48f,x+r*.32f,y-r*.10f) }
            p.style=Paint.Style.STROKE; p.strokeCap=Paint.Cap.ROUND; p.strokeWidth=side*.010f
            p.shader=LinearGradient(x-r*.7f,y-r*.6f,x+r*.2f,y-r*.2f,
                intArrayOf(0x00ffffff,0x24ffffff,0x0affffff,0x00ffffff),
                floatArrayOf(0f,.42f,.76f,1f),Shader.TileMode.CLAMP)
            canvas.drawPath(edge,p); p.shader=null
            canvas.restore()
        }
    }

    /** High-gloss black record with fine groove relief and curved light reflections. */
    private class VinylRecordDrawable : android.graphics.drawable.Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val side = minOf(bounds.width(), bounds.height()).toFloat()
            val x = bounds.exactCenterX()
            val y = bounds.exactCenterY()
            val r = side * .485f

            p.style = Paint.Style.FILL
            p.shader = RadialGradient(x-r*.28f, y-r*.34f, r*1.65f,
                intArrayOf(0xff202022.toInt(), 0xff0d0d0f.toInt(), 0xff020203.toInt()),
                floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, p)
            p.shader = null

            val recordClip = Path().apply { addCircle(x, y, r, Path.Direction.CW) }
            c.save()
            c.clipPath(recordClip)

            // Very fine concentric groove cuts, with a restrained raised edge on each groove.
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.BUTT
            for (i in 0..56) {
                val rr = r * (.285f + i * .0122f)
                p.strokeWidth = if (i % 4 == 0) side * .0052f else side * .0021f
                p.color = if (i % 2 == 0) 0x665b5b60 else 0xaa030304.toInt()
                c.drawCircle(x, y, rr, p)
                p.strokeWidth = side * .0018f
                p.color = 0x557b7b80
                c.drawCircle(x - side*.001f, y - side*.001f, rr - side*.003f, p)
                p.strokeWidth = side * .0016f
                p.color = 0x665f6064
                for (tick in 0 until 180) {
                    val a = tick * (Math.PI * 2.0 / 180.0)
                    val inner = rr - side * .0025f
                    val outer = rr + side * if (tick % 6 == 0) .0038f else .0018f
                    c.drawLine(x + kotlin.math.cos(a).toFloat() * inner, y + kotlin.math.sin(a).toFloat() * inner,
                        x + kotlin.math.cos(a).toFloat() * outer, y + kotlin.math.sin(a).toFloat() * outer, p)
                }
            }

            c.restore()

            // Label and spindle.
            p.style = Paint.Style.FILL
            p.color = 0xffb65c40.toInt(); c.drawCircle(x, y, r*.255f, p)
            p.color = 0xffdfbd96.toInt(); c.drawCircle(x, y, r*.216f, p)
            p.color = 0xff243e38.toInt(); c.drawCircle(x, y, r*.181f, p)
            p.color = 0xffefe1c6.toInt(); c.drawCircle(x, y, r*.028f, p)
        }
        override fun setAlpha(a: Int) { p.alpha = a }
        override fun setColorFilter(f: android.graphics.ColorFilter?) { p.colorFilter = f }
        @Deprecated("Deprecated in Android graphics API") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /**
     * Fixed bearing platform with a separate moving arm assembly. Only the arm, counterweight,
     * headshell and cartridge rotate; the pivot housing stays fixed on the deck.
     */
    private class RealTonearmArtwork {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        fun draw(c: Canvas, width: Int, height: Int, angle: Float) {
            if (width <= 0 || height <= 0) return
            c.save()
            c.scale(width / 276f, height / 348f)
            drawPivotBase(c)
            c.save()
            c.rotate(angle, 225f, 50f)
            drawMovingAssembly(c)
            c.restore()
            c.restore()
        }

        private fun drawPivotBase(c: Canvas) {
            p.style = Paint.Style.FILL
            // Flush oval mounting plate and beveled bearing collar.
            p.shader = LinearGradient(190f, 34f, 260f, 76f,
                intArrayOf(0xffded9d0.toInt(), 0xff77736d.toInt(), 0xffaaa59c.toInt(), 0xff4f4c47.toInt()),
                floatArrayOf(0f, .38f, .7f, 1f), Shader.TileMode.CLAMP)
            c.drawOval(RectF(190f, 29f, 260f, 76f), p)
            p.shader = null
            p.color = 0xff45433f.toInt()
            c.drawOval(RectF(196f, 33f, 254f, 72f), p)
            p.shader = RadialGradient(225f, 50f, 29f,
                intArrayOf(0xffeeeae2.toInt(), 0xffaaa69e.toInt(), 0xff5d5a55.toInt()),
                null, Shader.TileMode.CLAMP)
            c.drawCircle(225f, 50f, 28f, p)
            p.shader = null
            p.color = 0xff77736c.toInt(); c.drawCircle(225f, 50f, 22f, p)
            p.color = 0xffd9d4cb.toInt(); c.drawCircle(225f, 50f, 18f, p)
            p.shader = RadialGradient(220f, 43f, 19f,
                intArrayOf(0xfff8f5ee.toInt(), 0xffb7b2a9.toInt(), 0xff68645e.toInt()),
                null, Shader.TileMode.CLAMP)
            c.drawCircle(225f, 50f, 14f, p)
            p.shader = null
            p.color = 0xff57544f.toInt(); c.drawCircle(225f, 50f, 8f, p)
            p.color = 0xffd1cbc1.toInt(); c.drawCircle(225f, 50f, 5.5f, p)
            p.color = 0xff4c4944.toInt(); c.drawCircle(225f, 50f, 2.4f, p)

            // Three recessed fasteners make the bearing look mechanically mounted, not pasted on.
            val screws = arrayOf(204f to 49f, 245f to 39f, 242f to 64f)
            screws.forEach { (sx, sy) ->
                p.color = 0xff4a4742.toInt(); c.drawCircle(sx, sy, 2.8f, p)
                p.color = 0xffd8d2c8.toInt(); c.drawCircle(sx, sy, 1.5f, p)
                p.color = 0xff65615b.toInt(); c.drawLine(sx-1f, sy, sx+1f, sy, p)
            }
        }

        private fun stroke(c: Canvas, path: Path, color: Int, width: Float, cap: Paint.Cap = Paint.Cap.ROUND) {
            p.shader = null
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = width
            p.strokeCap = cap
            p.strokeJoin = Paint.Join.ROUND
            c.drawPath(path, p)
        }

        private fun drawMovingAssembly(c: Canvas) {
            p.style = Paint.Style.FILL
            // Rear stub and counterbalance: attached directly behind the pivot, not halfway down the arm.
            p.color = 0xff4d4a45.toInt()
            c.drawRoundRect(RectF(229f, 45f, 259f, 55f), 5f, 5f, p)
            p.shader = LinearGradient(249f, 34f, 274f, 61f,
                intArrayOf(0xfff3efe7.toInt(), 0xff8a867e.toInt(), 0xffd5d0c7.toInt(), 0xff5b5751.toInt()),
                floatArrayOf(0f, .36f, .68f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(250f, 35f, 274f, 64f), 12f, 12f, p)
            p.shader = null
            p.color = 0xff55514b.toInt(); c.drawOval(RectF(267f, 37f, 275f, 62f), p)
            p.color = 0xffc9c3b9.toInt(); c.drawOval(RectF(269f, 40f, 273f, 59f), p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.1f
            p.color = 0xff706b64.toInt()
            for (i in 0..4) c.drawLine(253f+i*3.5f, 39f, 253f+i*3.5f, 60f, p)

            // One continuous, gently S-curved satin-metal arm tube.
            val tube = Path().apply {
                moveTo(225f, 56f)
                cubicTo(238f, 94f, 207f, 151f, 181f, 192f)
                cubicTo(166f, 216f, 157f, 235f, 145f, 255f)
            }
            stroke(c, tube, 0xff3b3936.toInt(), 15f)
            p.shader = LinearGradient(215f, 65f, 145f, 255f,
                intArrayOf(0xfff3f0e9.toInt(), 0xff858179.toInt(), 0xffd9d4cb.toInt(), 0xff66625b.toInt()),
                floatArrayOf(0f, .38f, .62f, 1f), Shader.TileMode.CLAMP)
            p.color = Color.WHITE
            p.style = Paint.Style.STROKE
            p.strokeWidth = 11f
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            c.drawPath(tube, p)
            p.shader = null
            p.color = 0xfff8f5ee.toInt()
            p.strokeWidth = 2.1f
            c.drawPath(tube, p)
            p.color = 0xff77736c.toInt()
            p.strokeWidth = 1f
            c.drawPath(tube, p)

            // Tapered headshell, cartridge and stylus at the record end.
            val shell = Path().apply {
                moveTo(143f, 246f); lineTo(164f, 255f); lineTo(149f, 286f)
                lineTo(126f, 276f); close()
            }
            p.shader = LinearGradient(127f, 250f, 163f, 282f,
                intArrayOf(0xfff1ede5.toInt(), 0xff8d8981.toInt(), 0xffd8d2c9.toInt(), 0xff66625c.toInt()),
                null, Shader.TileMode.CLAMP)
            p.style = Paint.Style.FILL
            c.drawPath(shell, p)
            p.shader = null
            p.color = 0xff4e4b46.toInt()
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f
            c.drawPath(shell, p)
            p.style = Paint.Style.FILL
            p.color = 0xff3a3936.toInt()
            c.drawRoundRect(RectF(130f, 273f, 151f, 294f), 3f, 3f, p)
            p.color = 0xffaaa49a.toInt()
            c.drawCircle(135f, 278f, 2f, p); c.drawCircle(146f, 283f, 2f, p)
            p.color = 0xffded8ce.toInt()
            c.drawLine(139f, 294f, 136f, 310f, p)
            p.color = 0xff46433e.toInt()
            c.drawLine(136f, 309f, 134f, 316f, p)
            p.color = 0xffd9d2c8.toInt()
            c.drawCircle(134f, 316f, 1.7f, p)
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
}
