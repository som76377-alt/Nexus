package com.nexus.music.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import com.nexus.music.catalog.YoutubeSearchScope

/**
 * Home search controls and their presentation containers.
 *
 * Search execution and result rendering remain owned by MainActivity; this view only reports
 * search and scope intents and exposes the status/results slots for the Activity to populate.
 */
class SearchPanel(
    context: android.content.Context,
    initialScope: YoutubeSearchScope,
    private val onSearchRequested: () -> Unit,
    private val onScopeSelected: (YoutubeSearchScope) -> Unit
) : LinearLayout(context) {
    val queryInput: EditText
    val statusText = NexusUi.text(
        context,
        "",
        13f,
        NexusUi.Colors.muted
    ).apply {
        visibility = GONE
        setPadding(0, NexusUi.dp(context, 12), 0, NexusUi.dp(context, 8))
        setOnClickListener { onSearchRequested() }
    }

    val resultsContainer = LinearLayout(context).apply {
        orientation = VERTICAL
    }

    private var selectedScope = initialScope
    private val scopeToggleButton: Button

    init {
        orientation = VERTICAL

        val searchRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = NexusUi.rounded(context, NexusUi.Colors.surface, 16)
            setPadding(NexusUi.dp(context, 14), 0, NexusUi.dp(context, 6), 0)
        }

        queryInput = EditText(context).apply {
            hint = "Songs, artists, albums..."
            setHintTextColor(NexusUi.Colors.muted)
            setTextColor(NexusUi.Colors.foreground)
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, NexusUi.dp(context, 15), 0, NexusUi.dp(context, 15))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    onSearchRequested()
                    true
                } else {
                    false
                }
            }
        }

        scopeToggleButton = Button(context).apply {
            text = "Songs | All"
            textSize = 11f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minimumHeight = NexusUi.dp(context, 48)
            setPadding(NexusUi.dp(context, 10), 0, NexusUi.dp(context, 10), 0)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                onScopeSelected(
                    if (selectedScope == YoutubeSearchScope.MUSIC_SONGS) {
                        YoutubeSearchScope.EVERYTHING
                    } else {
                        YoutubeSearchScope.MUSIC_SONGS
                    }
                )
            }
        }

        searchRow.addView(queryInput, LayoutParams(0, NexusUi.dp(context, 54), 1f))
        searchRow.addView(scopeToggleButton, LayoutParams(-2, NexusUi.dp(context, 48)))
        addView(searchRow, LayoutParams(-1, -2).apply {
            bottomMargin = NexusUi.dp(context, 18)
        })

        addView(statusText, LayoutParams(-1, -2))
        addView(resultsContainer, LayoutParams(-1, -2))
        setSelectedScope(initialScope)
    }

    fun setSelectedScope(scope: YoutubeSearchScope) {
        selectedScope = scope
        val songsSelected = scope == YoutubeSearchScope.MUSIC_SONGS
        scopeToggleButton.isSelected = songsSelected
        scopeToggleButton.contentDescription =
            "Search scope: ${if (songsSelected) "Songs" else "All"}. Tap to switch."
        scopeToggleButton.setTextColor(
            if (songsSelected) NexusUi.Colors.accentForeground else NexusUi.Colors.foreground
        )
        scopeToggleButton.background = NexusUi.rounded(
            context,
            if (songsSelected) NexusUi.Colors.accent else NexusUi.Colors.raised,
            12
        )
    }
}
