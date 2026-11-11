package com.nexus.music.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Editorial Home discovery header. It only emits search intents; catalogue and playback
 * remain owned by MainActivity and the existing repository/service paths.
 */
class HomeDiscoveryView(
    context: Context,
    private val onExplore: (String) -> Unit,
    private val onProviderLab: () -> Unit
) : LinearLayout(context) {
    private val ink = NexusDesignSystem.Palette.carbonInk
    private val muted = NexusDesignSystem.Palette.muted

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, dp(22))

        addView(TextView(context).apply {
            text = "N E X U S     /     THE ART OF LISTENING"
            setOnLongClickListener { onProviderLab(); true }
            contentDescription = "Nexus. Long-press to open Provider Lab."
            textSize = 10f
            letterSpacing = 0.13f
            setTextColor(NexusDesignSystem.Palette.deepVinylGreen)
            typeface = NexusDesignSystem.Type.technical(context)
        }, LayoutParams(-1, -2))

        addView(TextView(context).apply {
            text = "Find your\nnext favourite."
            textSize = 36f
            setTextColor(ink)
            typeface = NexusDesignSystem.Type.display(context)
            setLineSpacing(dp(1).toFloat(), 0.96f)
            setPadding(0, dp(16), 0, dp(10))
        }, LayoutParams(-1, -2))

        addView(TextView(context).apply {
            text = "A listening room for discoveries, old records and new voices."
            textSize = 14f
            setTextColor(muted)
            typeface = NexusDesignSystem.Type.interfaceText(context)
        }, LayoutParams(-1, -2))

        val feature = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    NexusDesignSystem.Palette.deepVinylGreen,
                    Color.rgb(35, 64, 59)
                )
            ).apply { cornerRadius = dp(18).toFloat() }
            setPadding(dp(18), dp(16), dp(18), dp(18))
            setOnClickListener { onExplore("Indian independent music") }
            isClickable = true
            isFocusable = true
            contentDescription = "Explore independent Indian music"
        }
        feature.addView(TextView(context).apply {
            text = "THE NEXUS EDIT"
            textSize = 10f
            letterSpacing = 0.16f
            setTextColor(Color.rgb(226, 190, 126))
            typeface = NexusDesignSystem.Type.technical(context)
        })
        feature.addView(TextView(context).apply {
            text = "Beyond the\nmainstream."
            textSize = 28f
            setTextColor(NexusDesignSystem.Palette.sleeveWhite)
            typeface = NexusDesignSystem.Type.editorial(context)
            setPadding(0, dp(10), 0, dp(8))
        })
        feature.addView(TextView(context).apply {
            text = "Independent voices from across India     ↗"
            textSize = 12f
            setTextColor(Color.rgb(236, 222, 197))
            typeface = NexusDesignSystem.Type.interfaceText(context)
        })
        addView(feature, LayoutParams(-1, -2).apply {
            topMargin = dp(20)
            bottomMargin = dp(24)
        })

        addView(TextView(context).apply {
            text = "EXPLORE BY SOUND"
            textSize = 10f
            letterSpacing = 0.14f
            setTextColor(muted)
            typeface = NexusDesignSystem.Type.technical(context)
        }, LayoutParams(-1, -2))

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val chips = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(12), dp(4), dp(4))
        }
        val categories = listOf(
            Triple("Ghazal", "ghazal classics", NexusDesignSystem.Palette.oxblood),
            Triple("Indian indie", "Indian independent music", NexusDesignSystem.Palette.deepVinylGreen),
            Triple("Classical", "Indian classical music", NexusDesignSystem.Palette.cobalt),
            Triple("Instrumental", "instrumental music", NexusDesignSystem.Palette.plum),
            Triple("New voices", "new Indian music", NexusDesignSystem.Palette.terracotta),
            Triple("Evergreen", "evergreen Hindi songs", NexusDesignSystem.Palette.jade)
        )
        categories.forEach { (label, query, colour) ->
            val chip = TextView(context).apply {
                text = label
                textSize = 13f
                setTextColor(Color.WHITE)
                typeface = Typeface.create(NexusDesignSystem.Type.interfaceText(context), Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply {
                    setColor(colour)
                    cornerRadius = dp(9).toFloat()
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { onExplore(query) }
            }
            chips.addView(chip, LayoutParams(-2, dp(44)).apply { marginEnd = dp(9) })
        }
        scroll.addView(chips)
        addView(scroll, LayoutParams(-1, -2))

        addView(TextView(context).apply {
            text = "Search, then sort by popularity, title or duration."
            textSize = 11f
            setTextColor(muted)
            typeface = NexusDesignSystem.Type.interfaceText(context)
            setPadding(0, dp(8), 0, 0)
        }, LayoutParams(-1, -2))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
