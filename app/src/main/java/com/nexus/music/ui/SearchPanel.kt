package com.nexus.music.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.music.catalog.YoutubeSearchScope

/** Home search controls; result rendering and request ownership remain in MainActivity. */
class SearchPanel(
    context: Context,
    initialScope: YoutubeSearchScope,
    private val onSearchRequested: () -> Unit,
    private val onScopeSelected: (YoutubeSearchScope) -> Unit
) : LinearLayout(context) {
    val queryInput: EditText
    val clearButton: TextView
    val statusText = NexusUi.text(context, "", 13f, Theme.ColorToken.ink2).apply {
        visibility = GONE
        setPadding(0, NexusUi.dp(context, 14), 0, NexusUi.dp(context, 10))
    }
    val resultsContainer = LinearLayout(context).apply { orientation = VERTICAL }

    private var selectedScope = initialScope
    private val songsButton: Button
    private val allButton: Button

    init {
        orientation = VERTICAL
        val searchRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Drawables.rounded(context, Theme.ColorToken.paperStock, 16f).apply {
                setStroke(NexusUi.dp(context, 1), android.graphics.Color.argb(25, 65, 48, 28))
            }
            elevation = NexusUi.dp(context, 1).toFloat()
            setPadding(NexusUi.dp(context, 14), 0, NexusUi.dp(context, 4), 0)
        }
        val searchIcon = ImageView(context).apply {
            setImageDrawable(SearchGlyphDrawable(context))
            contentDescription = "Search"
        }
        searchRow.addView(searchIcon, LayoutParams(NexusUi.dp(context, 20), NexusUi.dp(context, 20)))
        queryInput = EditText(context).apply {
            hint = "Songs, artists, albums..."
            setHintTextColor(Theme.ColorToken.ink3)
            setTextColor(Theme.ColorToken.ink)
            textSize = 15f
            typeface = Type.body(context)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(NexusUi.dp(context, 12), 0, NexusUi.dp(context, 4), 0)
            minHeight = NexusUi.dp(context, 56)
            minimumHeight = NexusUi.dp(context, 56)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    onSearchRequested()
                    true
                } else false
            }
        }
        searchRow.addView(queryInput, LayoutParams(0, NexusUi.dp(context, 56), 1f))
        clearButton = TextView(context).apply {
            text = "×"
            textSize = 23f
            gravity = Gravity.CENTER
            minWidth = NexusUi.dp(context, 44)
            minHeight = NexusUi.dp(context, 48)
            contentDescription = "Clear search"
            setTextColor(Theme.ColorToken.ink2)
            visibility = GONE
            setOnClickListener { queryInput.text.clear(); onSearchRequested() }
        }
        searchRow.addView(clearButton, LayoutParams(NexusUi.dp(context, 44), NexusUi.dp(context, 48)))
        addView(searchRow, LayoutParams(-1, NexusUi.dp(context, 56)))

        queryInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearButton.visibility = if (s.isNullOrEmpty()) GONE else VISIBLE
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val scopes = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, NexusUi.dp(context, 12), 0, NexusUi.dp(context, 4))
        }
        songsButton = scopeButton(context, "Songs")
        allButton = scopeButton(context, "All")
        songsButton.setOnClickListener { onScopeSelected(YoutubeSearchScope.MUSIC_SONGS) }
        allButton.setOnClickListener { onScopeSelected(YoutubeSearchScope.EVERYTHING) }
        scopes.addView(songsButton, LayoutParams(-2, NexusUi.dp(context, 48)).apply {
            marginEnd = NexusUi.dp(context, 8)
        })
        scopes.addView(allButton, LayoutParams(-2, NexusUi.dp(context, 48)))
        addView(scopes, LayoutParams(-1, -2))
        addView(statusText, LayoutParams(-1, -2))
        addView(resultsContainer, LayoutParams(-1, -2))
        setSelectedScope(initialScope)
    }

    fun setSelectedScope(scope: YoutubeSearchScope) {
        selectedScope = scope
        styleScope(songsButton, scope == YoutubeSearchScope.MUSIC_SONGS)
        styleScope(allButton, scope == YoutubeSearchScope.EVERYTHING)
    }

    private fun scopeButton(context: Context, label: String) = Button(context).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minWidth = NexusUi.dp(context, 64)
        minimumWidth = NexusUi.dp(context, 64)
        minHeight = NexusUi.dp(context, 48)
        minimumHeight = NexusUi.dp(context, 48)
        typeface = Type.medium(context)
        setPadding(NexusUi.dp(context, 16), 0, NexusUi.dp(context, 16), 0)
    }

    private fun styleScope(button: Button, selected: Boolean) {
        button.isSelected = selected
        button.setTextColor(if (selected) Theme.ColorToken.paperStock else Theme.ColorToken.ink2)
        button.background = Drawables.rounded(
            context,
            if (selected) Theme.ColorToken.mineral else android.graphics.Color.TRANSPARENT,
            18f
        ).apply {
            if (!selected) setStroke(NexusUi.dp(context, 1), Theme.ColorToken.ink3)
        }
        button.contentDescription = "Search scope ${button.text}${if (selected) ", selected" else ""}"
    }

    private class SearchGlyphDrawable(context: Context) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.ColorToken.ink2
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val path = Path()
        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height()).toFloat()
            val left = b.left + (b.width() - s) / 2f
            val top = b.top + (b.height() - s) / 2f
            canvas.save()
            canvas.translate(left, top)
            canvas.scale(s / (24f * density), s / (24f * density))
            canvas.drawCircle(10f, 10f, 6.5f, paint)
            path.reset()
            path.moveTo(15f, 15f)
            path.lineTo(21f, 21f)
            canvas.drawPath(path, paint)
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android graphics API")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = (24 * density).toInt()
        override fun getIntrinsicHeight(): Int = (24 * density).toInt()
    }
}
