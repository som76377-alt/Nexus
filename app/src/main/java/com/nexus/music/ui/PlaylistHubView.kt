package com.nexus.music.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Local playlist index. It owns presentation; playlist persistence stays in LocalLibraryStore. */
class PlaylistHubView(
    context: Context,
    watchLaterCount: Int,
    playlists: List<Pair<String, Int>>,
    private val onOpen: (String) -> Unit,
    private val onCreate: () -> Unit,
    private val onBack: () -> Unit
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        setBackgroundColor(NexusDesignSystem.Palette.canvas)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(12))
        }
        header.addView(TextView(context).apply {
            text = "‹"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(NexusDesignSystem.Palette.foreground)
            contentDescription = "Back to Home"
            isClickable = true
            setOnClickListener { onBack() }
        }, LayoutParams(dp(48), dp(48)))
        header.addView(TextView(context).apply {
            text = "Playlists"
            textSize = 28f
            setTextColor(NexusDesignSystem.Palette.foreground)
            typeface = NexusDesignSystem.Type.display(context)
        }, LayoutParams(0, -2, 1f))
        header.addView(TextView(context).apply {
            text = "＋"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(NexusDesignSystem.Palette.foreground)
            contentDescription = "Create playlist"
            minWidth = dp(48)
            minHeight = dp(48)
            isClickable = true
            setOnClickListener { onCreate() }
        })
        addView(header)

        addView(playlistRow("Watch later", watchLaterCount), LayoutParams(-1, dp(68)))
        playlists.forEach { (name, count) ->
            addView(playlistRow(name, count), LayoutParams(-1, dp(68)))
        }
        if (watchLaterCount == 0 && playlists.isEmpty()) {
            addView(TextView(context).apply {
                text = "Create a playlist to keep tracks together."
                textSize = 14f
                setTextColor(NexusDesignSystem.Palette.muted)
                setPadding(dp(22), dp(24), dp(22), dp(24))
            })
        }
    }

    private fun playlistRow(name: String, count: Int) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22), 0, dp(22), 0)
        isClickable = true
        isFocusable = true
        contentDescription = "Open " + name
        setOnClickListener { onOpen(name) }
        addView(TextView(context).apply {
            text = name
            textSize = 15f
            setTextColor(NexusDesignSystem.Palette.foreground)
            typeface = Typeface.create(NexusDesignSystem.Type.interfaceText(context), Typeface.BOLD)
        }, LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = count.toString()
            textSize = 13f
            setTextColor(NexusDesignSystem.Palette.muted)
        })
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
