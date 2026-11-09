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
import android.widget.Button
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

    enum class NavigationTab { HOME, ROOM }

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

    fun actionButton(
        context: Context,
        value: String,
        primary: Boolean
    ): Button = Button(context).apply {
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

    fun bottomNavigation(
        context: Context,
        selected: NavigationTab,
        onHome: () -> Unit,
        onRoom: () -> Unit
    ): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(context, Colors.surface, 18)
            setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7))
        }

        fun tab(title: String, tab: NavigationTab, onClick: () -> Unit) =
            Button(context).apply {
                text = title
                textSize = 14f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                    minimumHeight = dp(context, Spacing.controlHeight)
                typeface = Typeface.DEFAULT_BOLD
                val isSelected = selected == tab
                setTextColor(if (isSelected) Colors.accentForeground else Colors.foreground)
                background = rounded(context, if (isSelected) Colors.accent else Colors.raised, 12)
                setOnClickListener { onClick() }
            }

        bar.addView(
            tab("Home", NavigationTab.HOME, onHome),
            LinearLayout.LayoutParams(0, dp(context, Spacing.controlHeight), 1f).apply {
                marginEnd = dp(context, 7)
            }
        )
        bar.addView(
            tab("Room", NavigationTab.ROOM, onRoom),
            LinearLayout.LayoutParams(0, dp(context, Spacing.controlHeight), 1f)
        )
        return bar
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
