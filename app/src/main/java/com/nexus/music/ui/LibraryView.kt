package com.nexus.music.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil3.load
import com.nexus.music.catalog.CatalogTrack
import com.nexus.music.CatalogFormatting

/** Flat list presentation shared by Saved, History, Watch later and individual playlists. */
class LibraryView(
    context: Context,
    title: String,
    tracks: List<CatalogTrack>,
    private val onPlay: (CatalogTrack) -> Unit,
    private val onRemove: ((CatalogTrack) -> Unit)?,
    private val onBack: () -> Unit,
    private val onMore: ((View, CatalogTrack) -> Unit)? = null
) : LinearLayout(context) {
    private val ink = NexusDesignSystem.Palette.foreground
    private val muted = NexusDesignSystem.Palette.muted

    init {
        orientation = VERTICAL
        setBackgroundColor(NexusDesignSystem.Palette.canvas)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(12))
        }
        val back = TextView(context).apply {
            text = "‹"
            textSize = 30f
            setTextColor(ink)
            gravity = Gravity.CENTER
            contentDescription = "Back to Home"
            isClickable = true
            isFocusable = true
            setOnClickListener { onBack() }
        }
        header.addView(back, LayoutParams(dp(48), dp(48)))
        header.addView(TextView(context).apply {
            text = title
            textSize = 28f
            setTextColor(ink)
            typeface = NexusDesignSystem.Type.display(context)
        }, LayoutParams(0, -2, 1f))
        addView(header)

        if (tracks.isEmpty()) {
            addView(TextView(context).apply {
                text = when (title) {
                    "History" -> "Tracks you play will appear here."
                    "Watch later" -> "Nothing saved to Watch later yet."
                    else -> "This list is empty."
                }
                textSize = 14f
                setTextColor(muted)
                setPadding(dp(22), dp(24), dp(22), dp(24))
            }, LayoutParams(-1, -2))
        } else {
            tracks.forEach { item ->
                addView(trackRow(item, onRemove), LayoutParams(-1, -2))
            }
        }
    }

    private fun trackRow(track: CatalogTrack, remove: ((CatalogTrack) -> Unit)?): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(8), dp(16), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onPlay(track) }
            contentDescription = "Play " + track.title
        }
        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(NexusDesignSystem.Palette.raised)
                cornerRadius = dp(NexusDesignSystem.Shape.media).toFloat()
            }
            clipToOutline = true
            contentDescription = "Artwork for " + track.title
        }
        track.thumbnailUrl?.let { art.load(it) }
        row.addView(art, LayoutParams(dp(58), dp(58)))

        val labels = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        labels.addView(TextView(context).apply {
            text = track.title.ifBlank { "Untitled" }
            textSize = 14f
            setTextColor(ink)
            typeface = Typeface.create(NexusDesignSystem.Type.interfaceText(context), Typeface.BOLD)
            maxLines = 2
        })
        labels.addView(TextView(context).apply {
            text = buildString {
                append(track.artist ?: "Unknown artist")
                track.durationSeconds?.let { append(" · ").append(CatalogFormatting.duration(it)) }
            }
            textSize = 12f
            setTextColor(muted)
            maxLines = 1
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(labels, LayoutParams(0, -2, 1f))

        if (remove != null) {
            row.addView(TextView(context).apply {
                text = "×"
                textSize = 22f
                setTextColor(muted)
                gravity = Gravity.CENTER
                minWidth = dp(48)
                minHeight = dp(48)
                contentDescription = "Remove " + track.title
                setOnClickListener { remove(track) }
            }, LayoutParams(dp(48), dp(48)))
        }
        if (onMore != null) {
            row.addView(TextView(context).apply {
                text = "⋮"
                textSize = 20f
                setTextColor(muted)
                gravity = Gravity.CENTER
                minWidth = dp(48)
                minHeight = dp(48)
                contentDescription = "More actions for " + track.title
                setOnClickListener { onMore.invoke(this, track) }
            }, LayoutParams(dp(48), dp(48)))
        }
        return row
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
