package com.nexus.music.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import java.lang.ref.WeakReference
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.text.TextUtils
import android.graphics.Typeface

/**
 * Shared, programmatic UI building blocks for Nexus Activities.
 *
 * Keep this layer presentation-only: navigation callbacks and playback actions are supplied
 * by the owning Activity, while room and playback state remain owned by their existing services.
 */
object NexusUi {
    object Colors {
        val background: Int = Theme.ColorToken.parchment
        val surface: Int = Theme.ColorToken.paperStock
        val raised: Int = Theme.ColorToken.paperStock
        val accent: Int = Theme.ColorToken.copper
        val foreground: Int = Theme.ColorToken.ink
        val muted: Int = Theme.ColorToken.ink2
        val accentForeground: Int = Theme.ColorToken.paperStock
    }

    object Spacing {
        const val screenHorizontal = 20
        const val screenVertical = 24
        const val controlHeight = 48
        const val navigationHeight = 62
        const val miniPlayerArtwork = 48
    }

    enum class NavigationTab { HOME, LIBRARY, ROOM, YOU }

    data class MiniPlayerViews(
        val container: LinearLayout,
        val progress: SeekBar,
        val artwork: ImageView,
        val title: TextView,
        val subtitle: TextView,
        val playPause: ImageButton
    )

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun rounded(context: Context, color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
        }

    fun text(
        context: Context,
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        maxLines: Int = Int.MAX_VALUE
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Type.medium(context)
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
    }

    fun actionButton(context: Context, value: String, primary: Boolean): Button =
        Button(context).apply {
            text = value
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(context, Spacing.controlHeight)
            minimumHeight = dp(context, Spacing.controlHeight)
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            setTextColor(if (primary) Colors.accentForeground else Colors.foreground)
            typeface = Type.medium(context)
            background = rounded(context, if (primary) Colors.accent else Colors.raised, 10)
        }

    @Volatile var unreadRoomCount: Int = 0
        private set
    private val unreadBadgeViews = mutableListOf<WeakReference<TextView>>()

    fun setUnreadCount(count: Int) {
        unreadRoomCount = count.coerceAtLeast(0)
        val iterator = unreadBadgeViews.iterator()
        while (iterator.hasNext()) {
            val view = iterator.next().get()
            if (view == null) iterator.remove() else updateUnreadBadge(view)
        }
    }

    private fun updateUnreadBadge(view: TextView) {
        view.text = if (unreadRoomCount > 99) "99+" else unreadRoomCount.toString()
        view.visibility = if (unreadRoomCount > 0) View.VISIBLE else View.GONE
    }

    fun bottomNavigation(
        context: Context,
        selected: NavigationTab,
        onHome: () -> Unit,
        onLibrary: () -> Unit,
        onRoom: () -> Unit,
        onYou: () -> Unit
    ): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(context, Colors.surface, 22)
            setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
            elevation = dp(context, 8).toFloat()
            contentDescription = "Main navigation"
        }
        val items = listOf(
            Triple(NavigationTab.HOME, "Home", onHome),
            Triple(NavigationTab.LIBRARY, "Library", onLibrary),
            Triple(NavigationTab.ROOM, "Room", onRoom),
            Triple(NavigationTab.YOU, "You", onYou)
        )
        items.forEach { (tab, label, callback) ->
            val active = selected == tab
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = if (active) rounded(context, Theme.ColorToken.mineral, 18)
                    else rounded(context, Color.TRANSPARENT, 18)
                isClickable = true
                isFocusable = true
                contentDescription = label
                setOnClickListener { callback() }
                setPadding(0, dp(context, 2), 0, dp(context, 2))
            }
            val iconFrame = FrameLayout(context)
            val icon = ImageView(context).apply {
                setImageDrawable(NavGlyphDrawable(context, tab, if (active) Colors.surface else Colors.muted))
                contentDescription = "$label tab"
            }
            iconFrame.addView(icon, FrameLayout.LayoutParams(dp(context, 21), dp(context, 21), Gravity.CENTER))
            if (tab == NavigationTab.ROOM) {
                val badge = TextView(context).apply {
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    minWidth = dp(context, 18)
                    minHeight = dp(context, 18)
                    setPadding(dp(context, 3), 0, dp(context, 3), 0)
                    setTextColor(Colors.surface)
                    background = rounded(context, Theme.ColorToken.unread, 10)
                    elevation = dp(context, 2).toFloat()
                    updateUnreadBadge(this)
                }
                unreadBadgeViews.add(WeakReference(badge))
                iconFrame.addView(badge, FrameLayout.LayoutParams(-2, dp(context, 18), Gravity.TOP or Gravity.END))
            }
            item.addView(iconFrame, LinearLayout.LayoutParams(-1, dp(context, 24)))
            item.addView(TextView(context).apply {
                text = label
                textSize = 11f
                typeface = if (active) Type.medium(context) else Type.body(context)
                setTextColor(if (active) Colors.surface else Colors.muted)
                gravity = Gravity.CENTER
                maxLines = 1
            }, LinearLayout.LayoutParams(-1, -2))
            Drawables.pressState(item)
            bar.addView(item, LinearLayout.LayoutParams(0, dp(context, 56), 1f).apply {
                marginStart = dp(context, 2)
                marginEnd = dp(context, 2)
            })
        }
        return bar
    }

    private class NavGlyphDrawable(
        context: Context,
        private val tab: NavigationTab,
        color: Int
    ) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height()).toFloat()
            val ox = b.left + (b.width() - s) / 2f
            val oy = b.top + (b.height() - s) / 2f
            canvas.save()
            canvas.translate(ox, oy)
            canvas.scale(s / (24f * density), s / (24f * density))
            path.reset()
            when (tab) {
                NavigationTab.HOME -> {
                    path.moveTo(3f, 11f); path.lineTo(12f, 3.5f); path.lineTo(21f, 11f)
                    path.moveTo(5.5f, 9.5f); path.lineTo(5.5f, 20f); path.lineTo(18.5f, 20f); path.lineTo(18.5f, 9.5f)
                    path.moveTo(9.5f, 20f); path.lineTo(9.5f, 14f); path.lineTo(14.5f, 14f); path.lineTo(14.5f, 20f)
                }
                NavigationTab.LIBRARY -> {
                    path.moveTo(4f, 4f); path.lineTo(4f, 20f); path.lineTo(20f, 20f); path.lineTo(20f, 4f); path.close()
                    path.moveTo(8f, 4f); path.lineTo(8f, 20f)
                    path.moveTo(11f, 8f); path.lineTo(17f, 8f)
                    path.moveTo(11f, 12f); path.lineTo(17f, 12f)
                }
                NavigationTab.ROOM -> {
                    path.moveTo(3f, 5f); path.lineTo(21f, 5f); path.lineTo(21f, 17f); path.lineTo(11f, 17f); path.lineTo(6f, 21f); path.lineTo(6f, 17f); path.lineTo(3f, 17f); path.close()
                    path.moveTo(7f, 10f); path.lineTo(17f, 10f)
                    path.moveTo(7f, 13f); path.lineTo(14f, 13f)
                }
                NavigationTab.YOU -> {
                    canvas.drawCircle(12f, 8f, 3.5f, paint)
                    path.moveTo(4f, 21f); path.cubicTo(4f, 14f, 20f, 14f, 20f, 21f)
                }
            }
            canvas.drawPath(path, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android graphics API")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = (24 * density).toInt()
        override fun getIntrinsicHeight(): Int = (24 * density).toInt()
    }

    fun miniPlayer(
        context: Context,
        onTogglePlayback: () -> Unit,
        onSeekStarted: () -> Unit,
        onSeekChanged: () -> Unit,
        onSeekFinished: (Int) -> Unit
    ): MiniPlayerViews {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(context, Colors.raised, 16)
            setPadding(dp(context, 14), dp(context, 5), dp(context, 14), dp(context, 12))
        }
        val progress = SeekBar(context).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Colors.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(Colors.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) onSeekChanged()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    onSeekStarted()
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    onSeekFinished(seekBar?.progress ?: 0)
                }
            })
        }
        panel.addView(progress, LinearLayout.LayoutParams(-1, dp(context, Spacing.controlHeight)))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val artwork = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(context, Colors.surface, 10)
            contentDescription = "Now playing artwork"
        }
        row.addView(
            artwork,
            LinearLayout.LayoutParams(
                dp(context, Spacing.miniPlayerArtwork),
                dp(context, Spacing.miniPlayerArtwork)
            )
        )

        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), 0, dp(context, 8), 0)
        }
        val title = text(context, "Nothing playing", 14f, Colors.foreground, bold = true, maxLines = 1)
        val subtitle = text(context, "Nothing playing", 12f, Colors.muted, maxLines = 1)
        labels.addView(title)
        labels.addView(subtitle.apply { setPadding(0, dp(context, 4), 0, 0) })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))

        val playPause = ImageButton(context).apply {
            contentDescription = "Play"
            isEnabled = false
            background = rounded(context, Colors.accent, 12)
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            setImageDrawable(PlaybackGlyphDrawable(context, playing = false))
            setOnClickListener { onTogglePlayback() }
        }
        row.addView(
            playPause,
            LinearLayout.LayoutParams(dp(context, 52), dp(context, Spacing.controlHeight))
        )
        panel.addView(row)
        return MiniPlayerViews(panel, progress, artwork, title, subtitle, playPause)
    }

    fun setPlaying(context: Context, button: ImageButton, playing: Boolean) {
        button.contentDescription = if (playing) "Pause" else "Play"
        button.setImageDrawable(PlaybackGlyphDrawable(context, playing))
    }

    /** Small path-based vector glyph; avoids font-dependent play/pause characters. */
    private class PlaybackGlyphDrawable(
        context: Context,
        private val playing: Boolean
    ) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Colors.accentForeground
            style = Paint.Style.FILL
        }

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            val size = minOf(width, height) * 0.58f
            val left = bounds.left + (width - size) / 2f
            val top = bounds.top + (height - size) / 2f
            if (playing) {
                val barWidth = size * 0.28f
                val gap = size * 0.18f
                canvas.drawRect(left, top, left + barWidth, top + size, paint)
                canvas.drawRect(left + barWidth + gap, top, left + size, top + size, paint)
            } else {
                val path = Path().apply {
                    moveTo(left, top)
                    lineTo(left + size, top + size / 2f)
                    lineTo(left, top + size)
                    close()
                }
                canvas.drawPath(path, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Android graphics API")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = (24 * density).toInt()
        override fun getIntrinsicHeight(): Int = (24 * density).toInt()
    }
}
