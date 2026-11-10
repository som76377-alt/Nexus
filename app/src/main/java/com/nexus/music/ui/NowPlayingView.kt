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
        tonearm.apply {
            setImageDrawable(RealTonearmDrawable())
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "Tonearm"
            visibility = View.VISIBLE
            // Pivot bearing remains outside the record; parent clipping is disabled.
            pivotX = dp(184).toFloat()
            pivotY = dp(43).toFloat()
            translationX = dp(58).toFloat()
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
        tonearm.animate().cancel()
        tonearm.animate()
            .rotation(if (playing) 0f else 31f)
            .translationY(0f)
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


    private class VinylRecordDrawable : android.graphics.drawable.Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val side = minOf(bounds.width(), bounds.height()).toFloat()
            val x = bounds.exactCenterX(); val y = bounds.exactCenterY(); val r = side * .485f
            p.shader = RadialGradient(x-r*.22f,y-r*.3f,r*1.5f,
                intArrayOf(0xff555558.toInt(),0xff29292b.toInt(),0xff101011.toInt()),
                floatArrayOf(0f,.52f,1f),Shader.TileMode.CLAMP)
            c.drawCircle(x,y,r,p); p.shader=null
            p.style=Paint.Style.STROKE
            for (i in 0..22) {
                val rr=r*(.31f+i*.029f)
                p.strokeWidth=if(i%4==0) side*.007f else side*.0025f
                p.color=if(i%2==0) 0x66595a5d else 0x77303032
                c.drawCircle(x,y,rr,p)
                p.color=0x447f8082
                c.drawCircle(x-side*.002f,y-side*.002f,rr-side*.004f,p)
            }
            p.style=Paint.Style.FILL
            p.shader=LinearGradient(x-r,y-r,x+r,y+r,
                intArrayOf(0x00ffffff,0x30ffffff,0x08ffffff,0x00ffffff),
                floatArrayOf(0f,.24f,.57f,1f),Shader.TileMode.CLAMP)
            c.save(); c.rotate(-35f,x,y)
            c.drawOval(RectF(x-r*.78f,y-r*.78f,x+r*.78f,y-r*.48f),p)
            c.restore(); p.shader=null
            p.style=Paint.Style.STROKE; p.strokeWidth=side*.016f; p.color=0x22ffffff
            c.drawArc(RectF(x-r*.91f,y-r*.91f,x+r*.91f,y+r*.91f),205f,110f,false,p)
            p.style=Paint.Style.FILL
            p.color=0xffb65c40.toInt(); c.drawCircle(x,y,r*.255f,p)
            p.color=0xffdfbd96.toInt(); c.drawCircle(x,y,r*.216f,p)
            p.color=0xff243e38.toInt(); c.drawCircle(x,y,r*.181f,p)
            p.color=0xffefe1c6.toInt(); c.drawCircle(x,y,r*.028f,p)
        }
        override fun setAlpha(a:Int) { p.alpha=a }
        override fun setColorFilter(f:android.graphics.ColorFilter?) { p.colorFilter=f }
        @Deprecated("Deprecated in Android graphics API") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
    }

    private class RealTonearmDrawable : android.graphics.drawable.Drawable() {
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        private fun line(c:Canvas,color:Int,w:Float,path:Path) {
            p.color=color; p.style=Paint.Style.STROKE; p.strokeWidth=w; p.strokeCap=Paint.Cap.ROUND
            c.drawPath(path,p)
        }
        override fun draw(c:Canvas) {
            c.save(); c.scale(bounds.width()/276f,bounds.height()/348f)
            p.style=Paint.Style.FILL
            p.shader=RadialGradient(225f,49f,34f,
                intArrayOf(0xffeee9df.toInt(),0xffaaa69e.toInt(),0xff55534f.toInt()),null,Shader.TileMode.CLAMP)
            c.drawCircle(225f,49f,33f,p); p.shader=null
            p.color=0xff77736d.toInt(); c.drawCircle(225f,49f,24f,p)
            p.color=0xffd7d2c9.toInt(); c.drawCircle(225f,49f,19f,p)
            p.color=0xff66635e.toInt(); c.drawCircle(225f,49f,12f,p)
            p.color=0xffc8c2b8.toInt(); c.drawCircle(225f,49f,7f,p)
            val arm=Path().apply { moveTo(224f,69f); cubicTo(219f,111f,178f,185f,139f,248f) }
            line(c,0xff45433f.toInt(),18f,arm); line(c,0xffc9c4bb.toInt(),12f,arm)
            line(c,0xfff4f0e8.toInt(),3f,arm); line(c,0xff89857d.toInt(),1f,arm)
            p.style=Paint.Style.FILL
            p.shader=LinearGradient(177f,180f,202f,207f,
                intArrayOf(0xfff0ece4.toInt(),0xff77736c.toInt(),0xffd0cbc2.toInt()),null,Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(177f,178f,201f,207f),7f,7f,p); p.shader=null
            p.color=0xff4b4945.toInt(); c.drawRoundRect(RectF(181f,184f,198f,203f),5f,5f,p)
            val head=Path().apply { moveTo(129f,238f); lineTo(151f,248f); lineTo(138f,278f); lineTo(115f,267f); close() }
            p.shader=LinearGradient(118f,245f,150f,275f,
                intArrayOf(0xffe5e0d7.toInt(),0xff77736c.toInt(),0xffc8c2b8.toInt()),null,Shader.TileMode.CLAMP)
            c.drawPath(head,p); p.shader=null
            p.color=0xff383735.toInt(); c.drawRoundRect(RectF(116f,263f,141f,286f),3f,3f,p)
            p.color=0xffc5beb3.toInt(); c.drawCircle(121f,266f,2f,p); c.drawCircle(136f,274f,2f,p)
            p.color=0xff33312f.toInt(); c.drawRect(125f,284f,128f,297f,p)
            p.color=0xffd6d0c6.toInt(); c.drawLine(126f,297f,124f,304f,p)
            p.style=Paint.Style.STROKE; p.strokeWidth=1f; p.color=0xff77736d.toInt()
            for(i in 0..4) c.drawLine(183f+i*3f,187f,183f+i*3f,194f,p)
            c.restore()
        }
        override fun setAlpha(a:Int) { p.alpha=a }
        override fun setColorFilter(f:android.graphics.ColorFilter?) { p.colorFilter=f }
        @Deprecated("Deprecated in Android graphics API") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
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
