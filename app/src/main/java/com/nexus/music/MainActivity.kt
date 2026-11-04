package com.nexus.music

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import coil3.load
import com.nexus.music.catalog.CatalogTrack
import com.nexus.music.catalog.ResolvedAudio
import com.nexus.music.catalog.YoutubeCatalogRepository
import com.nexus.music.catalog.YoutubeSearchScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { YoutubeCatalogRepository() }

    private lateinit var resultsContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var queryInput: EditText
    private lateinit var searchButton: Button
    private lateinit var scopeButtons: Map<YoutubeSearchScope, Button>

    private var selectedScope = YoutubeSearchScope.EVERYTHING
    private var isSearching = false
    private var isResolving = false

    private val background = Color.rgb(12, 14, 18)
    private val surface = Color.rgb(24, 28, 35)
    private val surfaceRaised = Color.rgb(31, 36, 44)
    private val accent = Color.rgb(167, 240, 106)
    private val primaryText = Color.rgb(244, 247, 250)
    private val secondaryText = Color.rgb(160, 172, 187)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(32))
        }

        content.addView(text("NEXUS", 12f, accent, bold = true).apply { letterSpacing = 0.22f })
        content.addView(text("Find your next\nfavourite track.", 31f, primaryText, bold = true).apply {
            setPadding(0, dp(12), 0, dp(8))
        })
        content.addView(text("Search YouTube's catalogue. Pick a result to prepare its audio.", 14f, secondaryText))

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 16)
            setPadding(dp(14), 0, dp(6), 0)
        }
        queryInput = EditText(this).apply {
            hint = "Songs, artists, albums..."
            setHintTextColor(secondaryText)
            setTextColor(primaryText)
            textSize = 15f
            singleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, dp(15), 0, dp(15))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { performSearch(); true } else false
            }
        }
        searchButton = Button(this).apply {
            text = "Search"
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.rgb(14, 20, 12))
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(accent, 12)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { performSearch() }
        }
        searchRow.addView(queryInput, LinearLayout.LayoutParams(0, dp(54), 1f))
        searchRow.addView(searchButton, LinearLayout.LayoutParams(-2, dp(42)))
        content.addView(searchRow, verticalParams(22))

        content.addView(text("SEARCH IN", 11f, secondaryText, bold = true).apply {
            letterSpacing = 0.12f
        }, verticalParams(24))

        val scopesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scopeMap = linkedMapOf(
            YoutubeSearchScope.EVERYTHING to "All",
            YoutubeSearchScope.MUSIC_SONGS to "Songs",
            YoutubeSearchScope.VIDEOS to "Videos"
        )
        val buttons = mutableMapOf<YoutubeSearchScope, Button>()
        scopeMap.forEach { (scope, label) ->
            val button = Button(this).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { selectScope(scope) }
            }
            buttons[scope] = button
            scopesRow.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) })
        }
        scopeButtons = buttons
        content.addView(scopesRow, verticalParams(10))
        renderScopeButtons()

        statusText = text("Search for something to get started.", 13f, secondaryText).apply {
            setPadding(0, dp(22), 0, dp(12))
        }
        content.addView(statusText)
        resultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(resultsContainer)

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun selectScope(scope: YoutubeSearchScope) {
        if (selectedScope == scope) return
        selectedScope = scope
        renderScopeButtons()
        if (queryInput.text.toString().isNotBlank()) performSearch()
    }

    private fun renderScopeButtons() {
        scopeButtons.forEach { (scope, button) ->
            val selected = scope == selectedScope
            button.setTextColor(if (selected) Color.rgb(14, 20, 12) else primaryText)
            button.background = rounded(if (selected) accent else surfaceRaised, 14)
        }
    }

    private fun performSearch() {
        val query = queryInput.text.toString().trim()
        if (query.isEmpty()) { queryInput.error = "Enter a song, artist or video"; return }
        if (isSearching) return

        isSearching = true
        searchButton.isEnabled = false
        searchButton.text = "Searching"
        statusText.text = "Searching YouTube..."
        resultsContainer.removeAllViews()

        activityScope.launch {
            try {
                val results = repository.search(query, selectedScope)
                resultsContainer.removeAllViews()
                if (results.isEmpty()) {
                    statusText.text = "No results found. Try another search or scope."
                } else {
                    statusText.text = "${results.size} results · tap one to prepare audio"
                    results.forEachIndexed { index, track ->
                        resultsContainer.addView(resultCard(track), verticalParams(if (index == 0) 0 else 10))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                statusText.text = "Search couldn't complete: ${error.message ?: "Please try again."}"
            } finally {
                isSearching = false
                searchButton.isEnabled = true
                searchButton.text = "Search"
            }
        }
    }

    private fun resultCard(track: CatalogTrack): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 16)
            setPadding(dp(10), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { resolve(track) }
        }
        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(surfaceRaised, 10)
            contentDescription = "Track thumbnail"
        }
        track.thumbnailUrl?.let { thumbnail.load(it) { crossfade(true) } }
        card.addView(thumbnail, LinearLayout.LayoutParams(dp(112), dp(72)))

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        details.addView(text(track.title.ifBlank { "Untitled" }, 14f, primaryText, bold = true, maxLines = 2))
        details.addView(text(track.artist ?: "Unknown artist", 12f, secondaryText, maxLines = 1).apply {
            setPadding(0, dp(5), 0, 0)
        })
        val metadata = listOfNotNull(
            track.durationSeconds?.takeIf { it >= 0 }?.let(CatalogFormatting::duration),
            track.viewCount?.takeIf { it >= 0 }?.let(CatalogFormatting::views)
        ).joinToString("  ·  ")
        if (metadata.isNotBlank()) details.addView(text(metadata, 11f, secondaryText).apply {
            setPadding(0, dp(5), 0, 0)
        })
        card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        return card
    }

    private fun resolve(track: CatalogTrack) {
        if (isResolving) return
        isResolving = true
        statusText.text = "Preparing audio for “${track.title}”..."
        activityScope.launch {
            try {
                val audio = repository.resolveAudio(track.url)
                showResolved(audio)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                statusText.text = "Couldn't prepare this track: ${error.message ?: "Please choose another result."}"
            } finally {
                isResolving = false
            }
        }
    }

    private fun showResolved(audio: ResolvedAudio) {
        statusText.text = "Audio stream resolved"
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surfaceRaised, 16)
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        panel.addView(text("READY FOR PLAYER", 10f, accent, bold = true).apply { letterSpacing = 0.12f })
        panel.addView(text(audio.title, 16f, primaryText, bold = true).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        panel.addView(text(audio.artist ?: "Unknown artist", 12f, secondaryText))
        val quality = audio.averageBitrate?.let { " · ${it / 1000} kbps" }.orEmpty()
        panel.addView(text("Audio stream prepared$quality", 12f, accent).apply {
            setPadding(0, dp(10), 0, 0)
        })
        resultsContainer.addView(panel, 0)
        Toast.makeText(this, "Track audio is ready for the player", Toast.LENGTH_SHORT).show()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false, maxLines: Int = Int.MAX_VALUE) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            this.maxLines = maxLines
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun verticalParams(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}
