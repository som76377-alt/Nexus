package com.nexus.music.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
    private val onExplore: (String, YoutubeSearchScope) -> Unit,
    private val onProviderLab: () -> Unit
) : LinearLayout(context) {
    private val ink = NexusDesignSystem.Palette.carbonInk
    private val muted = NexusDesignSystem.Palette.muted

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, dp(24))

        addView(TextView(context).apply {
            text = "NEXUS"
            setOnLongClickListener { onProviderLab(); true }
            contentDescription = "Nexus. Long-press to open Provider Lab."
            textSize = 11f
            letterSpacing = 0.09f
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
            text = "Discover music beyond your usual rotation."
            textSize = 14f
            setTextColor(muted)
            typeface = NexusDesignSystem.Type.interfaceText(context)
        }, LayoutParams(-1, -2))

        val feature = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                setColor(NexusDesignSystem.Palette.deepVinylGreen)
                cornerRadius = dp(4).toFloat()
            }
            setPadding(dp(18), dp(16), dp(18), dp(18))
            setOnClickListener { onExplore("Indian independent music") }
            isClickable = true
            isFocusable = true
            minimumHeight = dp(132)
            contentDescription = "Explore independent Indian music"
        }
        feature.addView(TextView(context).apply {
            text = "FROM INDIA"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(226, 210, 177))
            typeface = NexusDesignSystem.Type.technical(context)
        })
        feature.addView(TextView(context).apply {
            text = "Independent voices."
            textSize = 29f
            setTextColor(NexusDesignSystem.Palette.sleeveWhite)
            typeface = NexusDesignSystem.Type.display(context)
            setPadding(0, dp(12), 0, dp(6))
        })
        feature.addView(TextView(context).apply {
            text = "Explore the Indian independent scene  →"
            textSize = 12f
            setTextColor(Color.rgb(236, 222, 197))
            typeface = NexusDesignSystem.Type.interfaceText(context)
        })
        addView(feature, LayoutParams(-1, -2).apply {
            topMargin = dp(22)
            bottomMargin = dp(26)
        })

        addView(TextView(context).apply {
            text = "START WITH A SOUND"
            textSize = 10f
            letterSpacing = 0.08f
            setTextColor(muted)
            typeface = NexusDesignSystem.Type.technical(context)
        }, LayoutParams(-1, -2))

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val categoriesRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(6), dp(4), dp(2))
        }
        HomeDiscoveryCategoryCatalog.load(context)
            .flatMap { it.categories }
            .forEach { category ->
                val categoryView = TextView(context).apply {
                    text = category.label
                    textSize = 13f
                    setTextColor(ink)
                    typeface = Typeface.create(
                        NexusDesignSystem.Type.interfaceText(context),
                        Typeface.NORMAL
                    )
                    gravity = Gravity.CENTER
                    setPadding(dp(12), 0, dp(12), 0)
                    minimumHeight = dp(48)
                    isClickable = true
                    isFocusable = true
                    background = RippleDrawable(
                        ColorStateList.valueOf(Color.argb(24, 38, 33, 30)),
                        null,
                        null
                    )
                    setOnClickListener { onExplore(category.query, category.scope) }
                }
                categoriesRow.addView(categoryView, LayoutParams(-2, dp(48)).apply {
                    marginEnd = dp(4)
                })
            }
        scroll.addView(categoriesRow)
        addView(scroll, LayoutParams(-1, -2))

        addView(TextView(context).apply {
            text = "Search and sort by popularity, title or duration."
            textSize = 11f
            setTextColor(muted)
            typeface = NexusDesignSystem.Type.interfaceText(context)
            setPadding(0, dp(8), 0, 0)
        }, LayoutParams(-1, -2))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
