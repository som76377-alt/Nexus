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
import com.nexus.music.catalog.YoutubeSearchScope

data class HomeDiscoveryCategory(
    val id: String,
    val label: String,
    val query: String,
    val scope: YoutubeSearchScope
)

data class HomeDiscoveryGroup(
    val title: String,
    val categories: List<HomeDiscoveryCategory>
)

/**
 * Home's local library shortcuts and category browser.
 * Category labels and search intents come from genre-buckets.json; this view does no networking.
 */
class HomeDiscoveryView(
    context: Context,
    private val onSaved: () -> Unit,
    private val onHistory: () -> Unit,
    private val onPlaylists: () -> Unit,
    private val onBlend: () -> Unit,
    partnerConnected: Boolean,
    categories: List<HomeDiscoveryGroup>,
    private val onCategorySelected: (HomeDiscoveryCategory) -> Unit
) : LinearLayout(context) {
    private val ink = NexusDesignSystem.Palette.foreground
    private val muted = NexusDesignSystem.Palette.muted
    private val interfaceTypeface = NexusDesignSystem.Type.interfaceText(context)
    private val boldTypeface = Typeface.create(interfaceTypeface, Typeface.BOLD)

    init {
        orientation = VERTICAL
        setPadding(0, dp(10), 0, dp(22))

        addView(TextView(context).apply {
            text = "Nexus"
            textSize = 32f
            setTextColor(ink)
            typeface = interfaceTypeface
        }, LayoutParams(-1, -2))

        val shortcutsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val shortcuts = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(
            "Saved" to onSaved,
            "History" to onHistory,
            "Playlists" to onPlaylists
        ).forEach { (label, action) ->
            shortcuts.addView(shortcut(label, action), LayoutParams(-2, dp(48)).apply {
                marginEnd = dp(10)
            })
        }
        if (partnerConnected) {
            shortcuts.addView(shortcut("Blend", onBlend), LayoutParams(-2, dp(48)))
        }
        shortcutsScroll.addView(shortcuts)
        addView(shortcutsScroll, LayoutParams(-1, dp(52)))

        addView(TextView(context).apply {
            text = "Browse"
            textSize = 18f
            setTextColor(ink)
            typeface = boldTypeface
            setPadding(0, dp(12), 0, dp(5))
        }, LayoutParams(-1, -2))

        categories.filter { it.categories.isNotEmpty() }.forEach { group ->
            addView(TextView(context).apply {
                text = group.title
                textSize = 14f
                setTextColor(muted)
                typeface = boldTypeface
                setPadding(0, dp(7), 0, 0)
            }, LayoutParams(-1, -2))

            val categoryScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                setPadding(0, 0, dp(4), 0)
            }
            val categoryRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            group.categories.forEach { category ->
                categoryRow.addView(TextView(context).apply {
                    text = category.label
                    textSize = 13f
                    setTextColor(ink)
                    typeface = interfaceTypeface
                    gravity = Gravity.CENTER
                    minimumHeight = dp(48)
                    setPadding(dp(12), 0, dp(12), 0)
                    isClickable = true
                    isFocusable = true
                    background = RippleDrawable(
                        ColorStateList.valueOf(Color.argb(28, 255, 255, 255)),
                        null,
                        null
                    )
                    setOnClickListener { onCategorySelected(category) }
                }, LayoutParams(-2, dp(48)).apply {
                    marginEnd = dp(3)
                })
            }
            categoryScroll.addView(categoryRow)
            addView(categoryScroll, LayoutParams(-1, dp(50)))
        }
    }

    private fun shortcut(label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        setTextColor(ink)
        typeface = boldTypeface
        gravity = Gravity.CENTER
        minWidth = dp(48)
        minHeight = dp(48)
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            setColor(NexusDesignSystem.Palette.surface)
            cornerRadius = dp(NexusDesignSystem.Shape.control).toFloat()
        }
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
