package com.nexus.music.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object Widgets {
    fun copperButton(context: Context, label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            typeface = Type.medium(context)
            minWidth = 0
            minimumWidth = 0
            minHeight = Theme.Metrics.MIN_TOUCH_DP.px(context)
            minimumHeight = Theme.Metrics.MIN_TOUCH_DP.px(context)
            setTextColor(Theme.ColorToken.paperStock)
            background = Drawables.copperButton(context)
            setPadding(20.px(context), 0, 20.px(context), 0)
            contentDescription = label
            setOnClickListener { Motion.playHaptic(this); onClick() }
            Drawables.pressState(this)
        }

    fun paperCard(context: Context, paddingDp: Int = 16): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Drawables.paperCard(context)
            setPadding(paddingDp.px(context), paddingDp.px(context),
                paddingDp.px(context), paddingDp.px(context))
            Drawables.raised(this, 1f)
        }

    fun paperInput(context: Context, hintText: String): EditText =
        EditText(context).apply {
            hint = hintText
            textSize = 15f
            setTextColor(Theme.ColorToken.ink)
            setHintTextColor(Theme.ColorToken.ink3)
            typeface = Type.body(context)
            minHeight = 56.px(context)
            minimumHeight = 56.px(context)
            setPadding(16.px(context), 0, 16.px(context), 0)
            background = Drawables.rounded(context, Theme.ColorToken.paperStock, 14f)
            elevation = 1.px(context).toFloat()
        }

    fun chip(context: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 12f
            typeface = Type.medium(context)
            gravity = Gravity.CENTER
            minHeight = 36.px(context)
            setPadding(14.px(context), 0, 14.px(context), 0)
            setTextColor(if (selected) Theme.ColorToken.paperStock else Theme.ColorToken.ink2)
            background = Drawables.rounded(
                context,
                if (selected) Theme.ColorToken.mineral else android.graphics.Color.TRANSPARENT,
                18f
            ).apply {
                if (!selected) setStroke(1.px(context), Theme.ColorToken.ink3)
            }
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onClick() }
            Drawables.pressState(this)
        }

    fun segmentedChip(context: Context, label: String, selected: Boolean, onClick: () -> Unit) =
        chip(context, label, selected, onClick)

    fun badge(context: Context, label: String, unread: Boolean = false): TextView =
        TextView(context).apply {
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = 18.px(context)
            minHeight = 18.px(context)
            setPadding(4.px(context), 0, 4.px(context), 0)
            setTextColor(Theme.ColorToken.paperStock)
            background = Drawables.rounded(
                context,
                if (unread) Theme.ColorToken.unread else Theme.ColorToken.mineral,
                12f
            )
        }

    fun iconButton(context: Context, description: String, glyph: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = glyph
            textSize = 20f
            gravity = Gravity.CENTER
            minWidth = 48.px(context)
            minHeight = 48.px(context)
            contentDescription = description
            setTextColor(Theme.ColorToken.ink)
            background = Drawables.rounded(context, android.graphics.Color.TRANSPARENT, 24f)
            setOnClickListener { onClick() }
        }

    private fun Int.px(context: Context): Int =
        (this * context.resources.displayMetrics.density).toInt()
}
