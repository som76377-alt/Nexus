package com.nexus.music.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
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
        // Compatibility facade: existing screens migrate to semantic tokens incrementally.
        val background: Int get() = NexusDesignSystem.Palette.background
        val surface: Int get() = NexusDesignSystem.Palette.surface
        val raised: Int get() = NexusDesignSystem.Palette.raised
        val accent: Int get() = NexusDesignSystem.Palette.accent
        val foreground: Int get() = NexusDesignSystem.Palette.foreground
        val muted: Int get() = NexusDesignSystem.Palette.muted
        val accentForeground: Int get() = NexusDesignSystem.Palette.accentForeground
    }

    object Spacing {
        const val screenHorizontal = 20
        const val screenVertical = 24
        const val controlHeight = 48
        const val navigationHeight = 68
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
        typeface = if (bold) NexusDesignSystem.Type.interfaceText(context).let { Typeface.create(it, Typeface.BOLD) } else NexusDesignSystem.Type.interfaceText(context)
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
        typeface = Typeface.create(NexusDesignSystem.Type.interfaceText(context), Typeface.BOLD)
        background = rounded(context, if (primary) Colors.accent else Colors.raised, 10)
    }

    /**
     * Four quiet, line-drawn destinations sit directly on the screen canvas.
     * The selected state uses ink and a short rule rather than a filled button.
     */
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
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(context, 4), dp(context, 2), dp(context, 4), dp(context, 2))
        }

        fun tab(title: String, destination: NavigationTab, callback: () -> Unit): LinearLayout {
            val isSelected = selected == destination
            val colour = if (isSelected) Colors.accent else Colors.muted
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dp(context, 52)
                isClickable = true
                isFocusable = true
                contentDescription = title
                background = RippleDrawable(
                    ColorStateList.valueOf(Color.argb(28, 38, 33, 30)),
                    null,
                    null
                )
                setOnClickListener { callback() }

                addView(ImageView(context).apply {
                    setImageDrawable(NavigationGlyphDrawable(context, destination, colour))
                    contentDescription = null
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(context, 22), dp(context, 22)))

                addView(TextView(context).apply {
                    text = title
                    textSize = 11f
                    letterSpacing = 0.01f
                    setTextColor(colour)
                    typeface = Typeface.create(
                        NexusDesignSystem.Type.interfaceText(context),
                        if (isSelected) Typeface.BOLD else Typeface.NORMAL
                    )
                    gravity = Gravity.CENTER
                    setPadding(0, dp(context, 3), 0, 0)
                }, LinearLayout.LayoutParams(-2, -2))

                addView(View(context).apply {
                    setBackgroundColor(if (isSelected) Colors.accent else Color.TRANSPARENT)
                }, LinearLayout.LayoutParams(dp(context, 16), dp(context, 2)).apply {
                    topMargin = dp(context, 3)
                })
            }
        }

        val tabs = listOf(
            Triple("Home", NavigationTab.HOME, onHome),
            Triple("Library", NavigationTab.LIBRARY, onLibrary),
            Triple("Room", NavigationTab.ROOM, onRoom),
            Triple("You", NavigationTab.YOU, onYou)
        )
        tabs.forEach { (title, destination, callback) ->
            bar.addView(
                tab(title, destination, callback),
                LinearLayout.LayoutParams(0, dp(context, Spacing.navigationHeight), 1f)
            )
        }
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

    /** A compact, consistent stroke family drawn locally; no icon-font or library dependency. */
    private class NavigationGlyphDrawable(
        context: Context,
        private val destination: NavigationTab,
        colour: Int
    ) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colour
            style = Paint.Style.STROKE
            strokeWidth = 1.7f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val save = canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.scale(b.width() / 24f, b.height() / 24f)
            when (destination) {
                NavigationTab.HOME -> {
                    val roof = Path().apply {
                        moveTo(3.5f, 10.5f); lineTo(12f, 3.5f); lineTo(20.5f, 10.5f)
                    }
                    canvas.drawPath(roof, paint)
                    canvas.drawRoundRect(5.5f, 10f, 18.5f, 20.5f, 1.2f, 1.2f, paint)
                    canvas.drawLine(10f, 20.5f, 10f, 15.5f, paint)
                    canvas.drawLine(10f, 15.5f, 14f, 15.5f, paint)
                    canvas.drawLine(14f, 15.5f, 14f, 20.5f, paint)
                }
                NavigationTab.LIBRARY -> {
                    val left = Path().apply {
                        moveTo(12f, 6f); cubicTo(9f, 4.2f, 6f, 4.3f, 3.8f, 5.5f)
                        lineTo(3.8f, 19f); cubicTo(6.7f, 17.7f, 9.6f, 18.2f, 12f, 20f)
                    }
                    val right = Path().apply {
                        moveTo(12f, 6f); cubicTo(15f, 4.2f, 18f, 4.3f, 20.2f, 5.5f)
                        lineTo(20.2f, 19f); cubicTo(17.3f, 17.7f, 14.4f, 18.2f, 12f, 20f)
                    }
                    canvas.drawPath(left, paint)
                    canvas.drawPath(right, paint)
                    canvas.drawLine(7f, 9f, 9.5f, 9.5f, paint)
                    canvas.drawLine(14.5f, 9.5f, 17f, 9f, paint)
                }
                NavigationTab.ROOM -> {
                    canvas.drawCircle(8.5f, 8.2f, 2.5f, paint)
                    canvas.drawCircle(16.1f, 9f, 2.1f, paint)
                    val leftPerson = Path().apply {
                        moveTo(2.8f, 19.8f); cubicTo(3.4f, 15.8f, 5.2f, 13.8f, 8.7f, 13.8f)
                        cubicTo(12.1f, 13.8f, 13.8f, 16f, 14.2f, 19.8f)
                    }
                    val rightPerson = Path().apply {
                        moveTo(13.2f, 15f); cubicTo(17.3f, 13.7f, 20.3f, 16.1f, 21.2f, 19.8f)
                    }
                    canvas.drawPath(leftPerson, paint)
                    canvas.drawPath(rightPerson, paint)
                }
                NavigationTab.YOU -> {
                    canvas.drawCircle(12f, 8f, 3.1f, paint)
                    val shoulders = Path().apply {
                        moveTo(4f, 20f); cubicTo(4.7f, 15.5f, 7.2f, 13.7f, 12f, 13.7f)
                        cubicTo(16.8f, 13.7f, 19.3f, 15.5f, 20f, 20f)
                    }
                    canvas.drawPath(shoulders, paint)
                }
            }
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android graphics API")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = (24 * density).toInt()
        override fun getIntrinsicHeight(): Int = (24 * density).toInt()
    }
}
