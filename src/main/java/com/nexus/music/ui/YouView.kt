package com.nexus.music.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Local, account-free listening profile and app information.
 * This screen reads only the local library and package metadata; it does not
 * introduce an account system or change playback, room, or updater ownership.
 */
class YouView(
    context: Context,
    savedCount: Int,
    recentCount: Int,
    private val onLibrary: () -> Unit,
    private val onAbout: () -> Unit
) : LinearLayout(context) {
    private val ink = NexusDesignSystem.Palette.carbonInk
    private val muted = NexusDesignSystem.Palette.muted
    private val green = NexusDesignSystem.Palette.deepVinylGreen
    private val paper = NexusDesignSystem.Palette.archiveIvory
    private val sleeve = NexusDesignSystem.Palette.sleeveWhite

    init {
        orientation = VERTICAL
        setBackgroundColor(paper)

        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(34))
        }
        content.addView(text("N E X U S   /   PERSONAL ARCHIVE", 10f, green).apply {
            letterSpacing = .13f
            typeface = NexusDesignSystem.Type.technical(context)
        })
        content.addView(text("You,\nyour listening.", 36f, ink).apply {
            typeface = NexusDesignSystem.Type.display(context)
            setPadding(0, dp(16), 0, dp(8))
            setLineSpacing(dp(1).toFloat(), .96f)
        })
        content.addView(text(
            "A small home for the music you keep coming back to.",
            14f, muted
        ).apply { setPadding(0, 0, 0, dp(22)) })

        val identity = LinearLayout(context).apply {
            orientation = VERTICAL
            background = rounded(green, 18)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        identity.addView(text("YOUR NEXUS", 10f, Color.rgb(226, 190, 126)).apply {
            letterSpacing = .16f
            typeface = NexusDesignSystem.Type.technical(context)
        })
        identity.addView(text("Listening, kept personal.", 23f, sleeve).apply {
            typeface = NexusDesignSystem.Type.editorial(context)
            setPadding(0, dp(10), 0, dp(6))
        })
        identity.addView(text(
            "Your saved records and listening history stay on this device. No account is required.",
            12f, Color.rgb(236, 222, 197)
        ))
        content.addView(identity, LayoutParams(-1, -2))

        content.addView(text("YOUR COLLECTION", 10f, green).apply {
            letterSpacing = .15f
            typeface = NexusDesignSystem.Type.technical(context)
            setPadding(0, dp(26), 0, dp(10))
        })
        val stats = LinearLayout(context).apply { orientation = HORIZONTAL }
        stats.addView(statCard("SAVED RECORDS", savedCount.toString()), LayoutParams(0, -2, 1f).apply {
            marginEnd = dp(8)
        })
        stats.addView(statCard("RECENTLY PLAYED", recentCount.toString()), LayoutParams(0, -2, 1f))
        content.addView(stats)

        content.addView(actionRow(
            "Open your collection",
            "Saved tracks and recently played",
            "↗",
            onLibrary
        ), LayoutParams(-1, -2).apply { topMargin = dp(12) })

        content.addView(text("ABOUT THE APP", 10f, NexusDesignSystem.Palette.oxblood).apply {
            letterSpacing = .15f
            typeface = NexusDesignSystem.Type.technical(context)
            setPadding(0, dp(28), 0, dp(10))
        })
        val version = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Version unavailable"
        content.addView(actionRow(
            "About Nexus",
            "Version $version · updates checked when the app opens",
            "i",
            onAbout
        ), LayoutParams(-1, -2))
        content.addView(text(
            "Made for discovering music, keeping favourites and listening together.",
            12f, muted
        ).apply { setPadding(dp(2), dp(18), dp(2), 0) })

        addView(content, LayoutParams(-1, -2))
    }

    private fun statCard(label: String, value: String) = LinearLayout(context).apply {
        orientation = VERTICAL
        background = rounded(sleeve, 13)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        addView(text(value, 30f, ink).apply {
            typeface = NexusDesignSystem.Type.display(context)
        })
        addView(text(label, 9f, muted).apply {
            letterSpacing = .08f
            typeface = NexusDesignSystem.Type.technical(context)
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun actionRow(title: String, subtitle: String, marker: String, action: () -> Unit) =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(sleeve, 12)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            val labels = LinearLayout(context).apply { orientation = VERTICAL }
            labels.addView(text(title, 14f, ink).apply {
                typeface = Typeface.create(NexusDesignSystem.Type.interfaceText(context), Typeface.BOLD)
            })
            labels.addView(text(subtitle, 11f, muted).apply { setPadding(0, dp(4), 0, 0) })
            addView(labels, LayoutParams(0, -2, 1f))
            addView(text(marker, 19f, green).apply { gravity = Gravity.CENTER })
        }

    private fun text(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = NexusDesignSystem.Type.interfaceText(context)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
