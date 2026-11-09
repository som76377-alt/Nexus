package com.nexus.music.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewOutlineProvider

object Drawables {
    fun rounded(context: Context, color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * context.resources.displayMetrics.density
        }

    fun paperCard(context: Context): GradientDrawable =
        rounded(context, Theme.ColorToken.paperStock, Theme.Metrics.CARD_RADIUS_DP.toFloat())

    fun copperButton(context: Context): RippleDrawable {
        val density = context.resources.displayMetrics.density
        val content = rounded(context, Theme.ColorToken.copper, 14f)
        content.setStroke((density).toInt().coerceAtLeast(1), Color.argb(28, 255, 255, 255))
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(34, 255, 255, 255)),
            content,
            rounded(context, Color.WHITE, 14f)
        )
    }

    fun raised(view: View, elevationDp: Float = 2f) {
        val density = view.resources.displayMetrics.density
        view.elevation = elevationDp * density
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = false
    }

    fun floating(view: View) = raised(view, 8f)

    fun pressState(view: View) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> Motion.press(target, true)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> Motion.press(target, false)
            }
            false
        }
    }
}
