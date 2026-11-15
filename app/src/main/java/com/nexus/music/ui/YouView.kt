package com.nexus.music.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Purpose-only account, room, effects and version settings. */
class YouView(
    context: Context,
    partnerStatus: String,
    roomStatus: String,
    reduceEffects: Boolean,
    hapticsEnabled: Boolean,
    private val onReduceEffectsChanged: (Boolean) -> Unit,
    private val onHapticsChanged: (Boolean) -> Unit,
    private val onAbout: () -> Unit
) : LinearLayout(context) {
    private val ink = NexusDesignSystem.Palette.foreground
    private val muted = NexusDesignSystem.Palette.muted

    init {
        orientation = VERTICAL
        setBackgroundColor(NexusDesignSystem.Palette.canvas)
        setPadding(dp(22), dp(24), dp(22), dp(32))

        addView(TextView(context).apply {
            text = "You"
            textSize = 32f
            setTextColor(ink)
            typeface = NexusDesignSystem.Type.display(context)
        }, LayoutParams(-1, -2))

        addView(sectionTitle("Account and connection"), LayoutParams(-1, -2).apply {
            topMargin = dp(28)
            bottomMargin = dp(8)
        })
        addView(statusRow("Partner", partnerStatus))
        addView(statusRow("Room", roomStatus))

        addView(sectionTitle("Appearance and response"), LayoutParams(-1, -2).apply {
            topMargin = dp(28)
            bottomMargin = dp(8)
        })
        addView(settingRow("Reduce effects", "Use fewer visual effects", reduceEffects, onReduceEffectsChanged))
        addView(settingRow("Haptics", "Vibration on selected actions", hapticsEnabled, onHapticsChanged))

        addView(sectionTitle("Application"), LayoutParams(-1, -2).apply {
            topMargin = dp(28)
            bottomMargin = dp(8)
        })
        val version = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Unavailable"
        addView(statusRow("Version", version))
        addView(TextView(context).apply {
            text = "About Nexus"
            textSize = 14f
            setTextColor(ink)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(52)
            isClickable = true
            isFocusable = true
            setOnClickListener { onAbout() }
        }, LayoutParams(-1, -2))
    }

    private fun sectionTitle(value: String) = TextView(context).apply {
        text = value
        textSize = 17f
        setTextColor(ink)
        typeface = Typeface.create(NexusDesignSystem.Type.interfaceText(context), Typeface.BOLD)
    }

    private fun statusRow(label: String, value: String) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setMinimumHeight(dp(48))
        addView(TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(ink)
        }, LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = value
            textSize = 13f
            setTextColor(muted)
            gravity = Gravity.END
        })
    }

    private fun settingRow(
        title: String,
        summary: String,
        enabled: Boolean,
        onChanged: (Boolean) -> Unit
    ) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setMinimumHeight(dp(64))
        val labels = LinearLayout(context).apply { orientation = VERTICAL }
        labels.addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(ink)
        })
        labels.addView(TextView(context).apply {
            text = summary
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(3), 0, 0)
        })
        addView(labels, LayoutParams(0, -2, 1f))
        addView(Switch(context).apply {
            isChecked = enabled
            contentDescription = title
            setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        }, LayoutParams(-2, -2))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
