package com.nexus.music.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Home's compact library shortcuts. This view emits navigation intents only;
 * local data and catalogue work remain owned by MainActivity.
 */
class HomeDiscoveryView(
    context: Context,
    private val onSaved: () -> Unit,
    private val onHistory: () -> Unit,
    private val onPlaylists: () -> Unit,
    private val onBlend: () -> Unit,
    partnerConnected: Boolean
) : LinearLayout(context) {
    private val ink = NexusDesignSystem.Palette.foreground

    init {
        orientation = VERTICAL
        setPadding(0, dp(10), 0, dp(22))

        addView(TextView(context).apply {
            text = "Nexus"
            textSize = 32f
            setTextColor(ink)
            typeface = NexusDesignSystem.Type.display(context)
        }, LayoutParams(-1, -2))

        addView(TextView(context).apply {
            text = "Recommendations"
            textSize = 19f
            setTextColor(ink)
            typeface = Typeface.create(NexusDesignSystem.Type.interfaceText(context), Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(12))
        }, LayoutParams(-1, -2))

        val scroll = HorizontalScrollView(context).apply {
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
            shortcuts.addView(shortcut(label, action), LayoutParams(-2, dp(44)).apply {
                marginEnd = dp(12)
            })
        }
        if (partnerConnected) shortcuts.addView(shortcut("Blend", onBlend), LayoutParams(-2, dp(44)))
        scroll.addView(shortcuts)
        addView(scroll, LayoutParams(-1, dp(48)))
    }

    private fun shortcut(label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 15f
        setTextColor(ink)
        typeface = Typeface.create(NexusDesignSystem.Type.interfaceText(context), Typeface.BOLD)
        gravity = Gravity.CENTER
        minWidth = dp(48)
        minHeight = dp(44)
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
