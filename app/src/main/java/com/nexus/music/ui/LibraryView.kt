package com.nexus.music.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil3.load
import com.nexus.music.catalog.CatalogTrack
import com.nexus.music.CatalogFormatting

/** Library presentation for locally saved tracks and recently played source records. */
class LibraryView(
    context: Context,
    saved: List<CatalogTrack>,
    recent: List<CatalogTrack>,
    private val onPlay: (CatalogTrack) -> Unit,
    private val onRemove: (CatalogTrack) -> Unit,
    private val onBack: () -> Unit
) : LinearLayout(context) {
    private val ink = NexusDesignSystem.Palette.carbonInk
    private val muted = NexusDesignSystem.Palette.muted
    private val paper = NexusDesignSystem.Palette.archiveIvory

    init {
        orientation = VERTICAL
        setBackgroundColor(paper)
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(10))
        }
        val back = TextView(context).apply {
            text = "‹"
            textSize = 32f
            setTextColor(ink)
            gravity = Gravity.CENTER
            contentDescription = "Back to Home"
            setOnClickListener { onBack() }
        }
        header.addView(back, LayoutParams(dp(44), dp(48)))
        val title = TextView(context).apply {
            text = "Your collection"
            textSize = 29f
            setTextColor(ink)
            typeface = NexusDesignSystem.Type.display(context)
        }
        header.addView(title, LayoutParams(0, -2, 1f))
        header.addView(TextView(context).apply {
            text = "N / ARCHIVE"
            textSize = 9f
            letterSpacing = .12f
            setTextColor(NexusDesignSystem.Palette.deepVinylGreen)
            typeface = NexusDesignSystem.Type.technical(context)
        })
        addView(header)

        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(30))
        }
        content.addView(TextView(context).apply {
            text = "SAVED RECORDS"
            textSize = 10f
            letterSpacing = .16f
            setTextColor(NexusDesignSystem.Palette.deepVinylGreen)
            typeface = NexusDesignSystem.Type.technical(context)
        })
        content.addView(TextView(context).apply {
            text = if (saved.isEmpty()) "Keep the records you want to return to." else "${saved.size} records in your collection"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(5), 0, dp(12))
        })
        if (saved.isEmpty()) {
            content.addView(emptyNote("Your collection is waiting. Save tracks from search results to keep them here."))
        } else {
            saved.forEach { track -> content.addView(trackRow(track, removable = true), LayoutParams(-1, -2).apply { bottomMargin = dp(9) }) }
        }

        val divider = GradientDrawable().apply { setColor(Color.rgb(211, 198, 177)); cornerRadius = dp(1).toFloat() }
        content.addView(android.view.View(context).apply { background = divider }, LayoutParams(-1, dp(1)).apply {
            topMargin = dp(18); bottomMargin = dp(18)
        })
        content.addView(TextView(context).apply {
            text = "RECENTLY PLAYED"
            textSize = 10f
            letterSpacing = .16f
            setTextColor(NexusDesignSystem.Palette.oxblood)
            typeface = NexusDesignSystem.Type.technical(context)
        })
        content.addView(TextView(context).apply {
            text = "A short history on this device"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(5), 0, dp(12))
        })
        if (recent.isEmpty()) {
            content.addView(emptyNote("Tracks you play will appear here."))
        } else {
            recent.forEach { track -> content.addView(trackRow(track, removable = false), LayoutParams(-1, -2).apply { bottomMargin = dp(9) }) }
        }
        addView(content, LayoutParams(-1, -2))
    }

    private fun trackRow(track: CatalogTrack, removable: Boolean): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(NexusDesignSystem.Palette.sleeveWhite)
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(9), dp(9), dp(9), dp(9))
            isClickable = true
            isFocusable = true
            setOnClickListener { onPlay(track) }
            contentDescription = "Play ${track.title}"
        }
        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(NexusDesignSystem.Palette.ochre); cornerRadius = dp(8).toFloat() }
            contentDescription = "Artwork for ${track.title}"
        }
        track.thumbnailUrl?.let { art.load(it) }
        row.addView(art, LayoutParams(dp(58), dp(58)))
        val labels = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(11), 0, dp(6), 0)
        }
        labels.addView(TextView(context).apply {
            text = track.title.ifBlank { "Untitled" }
            textSize = 13f
            setTextColor(ink)
            typeface = Typeface.create(NexusDesignSystem.Type.interfaceText(context), Typeface.BOLD)
            maxLines = 2
        })
        labels.addView(TextView(context).apply {
            text = buildString {
                append(track.artist ?: "Unknown artist")
                track.durationSeconds?.let { append("  ·  ").append(CatalogFormatting.duration(it)) }
            }
            textSize = 11f
            setTextColor(muted)
            maxLines = 1
        })
        row.addView(labels, LayoutParams(0, -2, 1f))
        if (removable) {
            row.addView(TextView(context).apply {
                text = "×"
                textSize = 23f
                setTextColor(NexusDesignSystem.Palette.oxblood)
                gravity = Gravity.CENTER
                contentDescription = "Remove from saved records"
                setOnClickListener { onRemove(track) }
            }, LayoutParams(dp(38), dp(48)))
        } else {
            row.addView(TextView(context).apply {
                text = "▶"
                textSize = 13f
                setTextColor(NexusDesignSystem.Palette.deepVinylGreen)
                gravity = Gravity.CENTER
            }, LayoutParams(dp(38), dp(48)))
        }
        return row
    }

    private fun emptyNote(message: String) = TextView(context).apply {
        text = message
        textSize = 13f
        setTextColor(muted)
        setPadding(dp(14), dp(16), dp(14), dp(16))
        background = GradientDrawable().apply {
            setColor(NexusDesignSystem.Palette.sleeveWhite)
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
