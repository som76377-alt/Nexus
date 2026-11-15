package com.nexus.music.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout

/** View-based material placeholder. Real photographed assets are injected only through tokens. */
class MaterialSurface(
    context: Context,
    role: Role
) : FrameLayout(context) {
    enum class Role { CANVAS, LARGE_SURFACE, CONTROL }

    init {
        val color = when (role) {
            Role.CANVAS -> NexusDesignSystem.Palette.canvas
            Role.LARGE_SURFACE -> NexusDesignSystem.Palette.surface
            Role.CONTROL -> NexusDesignSystem.Palette.raised
        }
        setBackgroundColor(color)
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    companion object {
        fun background(context: Context, role: Role): GradientDrawable =
            GradientDrawable().apply {
                setColor(
                    when (role) {
                        Role.CANVAS -> NexusDesignSystem.Palette.canvas
                        Role.LARGE_SURFACE -> NexusDesignSystem.Palette.surface
                        Role.CONTROL -> NexusDesignSystem.Palette.raised
                    }
                )
                cornerRadius = NexusUi.dp(context, when (role) {
                    Role.CANVAS -> 0
                    Role.LARGE_SURFACE -> NexusDesignSystem.Shape.large
                    Role.CONTROL -> NexusDesignSystem.Shape.control
                }).toFloat()
            }
    }
}
