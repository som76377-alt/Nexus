package com.nexus.music

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import coil3.load
import com.nexus.music.catalog.YoutubeCatalogRepository
import com.nexus.music.library.LibraryRepository
import com.nexus.music.library.LibraryTrack
import com.nexus.music.library.PlaylistSummary
import com.nexus.music.library.SqliteLibraryRepository
import com.nexus.music.playback.PlaybackConnection
import com.nexus.music.playback.PlaybackSnapshot
import com.nexus.music.ui.Drawables
import com.nexus.music.ui.NexusUi
import com.nexus.music.ui.Theme
import com.nexus.music.ui.Type
import com.nexus.music.ui.Widgets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

abstract class NexusPlaceholderActivity : Activity() {
    protected abstract val selectedTab: NexusUi.NavigationTab
    protected abstract val pageTitle: String
    protected abstract val eyebrow: String
    protected abstract val emptyTitle: String
    protected abstract val emptyMessage: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.ColorToken.parchment
        window.navigationBarColor = Theme.ColorToken.parchment
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.ColorToken.parchment)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        content.addView(NexusUi.text(this, "Nexus", 32f, Theme.ColorToken.ink).apply {
            typeface = Type.title(this@NexusPlaceholderActivity)
            letterSpacing = -0.02f
        })
        content.addView(NexusUi.text(this, eyebrow.uppercase(), 11f, Theme.ColorToken.mineral).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(24), 0, dp(8))
        })
        content.addView(NexusUi.text(this, pageTitle, 28f, Theme.ColorToken.ink, bold = true))
        val card = Widgets.paperCard(this, 22).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
        }
        card.addView(NexusUi.text(this, emptyTitle, 20f, Theme.ColorToken.ink, bold = true))
        card.addView(NexusUi.text(this, emptyMessage, 14f, Theme.ColorToken.ink2).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        })
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(
            NexusUi.bottomNavigation(
                context = this,
                selected = selectedTab,
                onHome = { navigate(MainActivity::class.java) },
                onLibrary = { navigate(LibraryActivity::class.java) },
                onRoom = { navigate(com.nexus.music.rooms.ListeningRoomActivity::class.java) },
                onYou = { navigate(ProfileActivity::class.java) }
            ),
            LinearLayout.LayoutParams(-1, dp(68)).apply {
                leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(10)
            }
        )
        setContentView(root)
        applySystemBarInsets(root)
    }

    private fun navigate(destination: Class<out Activity>) {
        if (destination == javaClass) return
        startActivity(Intent(this, destination).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        overridePendingTransition(R.anim.nexus_page_enter, R.anim.nexus_page_exit)
    }

    private fun applySystemBarInsets(root: LinearLayout) {
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        root.requestApplyInsets()
    }

    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** Local collections UI. Playback still resolves the saved catalogue URL through the existing path. */
class LibraryActivity : Activity() {
    private enum class Section { LIKED, SAVED, PLAYLISTS }

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val library: LibraryRepository by lazy { SqliteLibraryRepository(applicationContext) }
    private val catalog by lazy { YoutubeCatalogRepository() }
    private var playback: PlaybackConnection? = null
    private var resolveJob: Job? = null
    private var section = Section.LIKED
    private var openedPlaylistId: Long? = null
    private var openedPlaylistName: String? = null
    private lateinit var content: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.ColorToken.parchment
        window.navigationBarColor = Theme.ColorToken.parchment
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.ColorToken.parchment)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(30))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(
            NexusUi.bottomNavigation(
                context = this,
                selected = NexusUi.NavigationTab.LIBRARY,
                onHome = { navigate(MainActivity::class.java) },
                onLibrary = {},
                onRoom = { navigate(com.nexus.music.rooms.ListeningRoomActivity::class.java) },
                onYou = { navigate(ProfileActivity::class.java) }
            ),
            LinearLayout.LayoutParams(-1, dp(68)).apply {
                leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(10)
            }
        )
        setContentView(root)
        applySystemBarInsets(root)
        playback = PlaybackConnection(
            context = this,
            onReady = {},
            onStateChanged = {},
            onError = { message -> Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        )
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(NexusUi.text(this, "Nexus", 32f, Theme.ColorToken.ink).apply {
            typeface = Type.title(this@LibraryActivity)
            letterSpacing = -0.02f
        })
        content.addView(NexusUi.text(this, "COLLECTED AND KEPT", 11f, Theme.ColorToken.mineral).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(24), 0, dp(8))
        })
        val heading = openedPlaylistName ?: "Your library"
        content.addView(NexusUi.text(this, heading, 28f, Theme.ColorToken.ink, bold = true))
        if (openedPlaylistId != null) {
            content.addView(TextView(this).apply {
                text = "‹  All playlists"
                textSize = 13f
                typeface = Type.medium(this@LibraryActivity)
                setTextColor(Theme.ColorToken.mineral)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                contentDescription = "Back to playlists"
                setOnClickListener { openedPlaylistId = null; openedPlaylistName = null; render() }
            })
        } else {
            tabs = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(18), 0, dp(8))
            }
            addTab("Liked", Section.LIKED)
            addTab("Saved", Section.SAVED)
            addTab("Playlists", Section.PLAYLISTS)
            content.addView(tabs, LinearLayout.LayoutParams(-1, -2))
        }

        when {
            openedPlaylistId != null -> renderTracks(library.playlistTracks(openedPlaylistId!!))
            section == Section.LIKED -> renderTracks(library.likedTracks())
            section == Section.SAVED -> renderTracks(library.savedTracks())
            else -> renderPlaylists()
        }
    }

    private fun addTab(label: String, value: Section) {
        val chip = Widgets.chip(this, label, section == value) {
            section = value
            openedPlaylistId = null
            openedPlaylistName = null
            render()
        }
        tabs.addView(chip, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginEnd = if (value != Section.PLAYLISTS) dp(8) else 0
        })
    }

    private fun renderTracks(tracks: List<LibraryTrack>) {
        if (tracks.isEmpty()) {
            val title = when {
                openedPlaylistId != null -> "This playlist is empty"
                section == Section.LIKED -> "No liked tracks yet"
                else -> "Nothing saved yet"
            }
            val message = when {
                openedPlaylistId != null -> "Add tracks from the player to build this collection."
                section == Section.LIKED -> "Like a track while listening and it will be kept here."
                else -> "Save tracks you want to find again and they will appear here."
            }
            val card = Widgets.paperCard(this, 20)
            card.addView(NexusUi.text(this, title, 19f, Theme.ColorToken.ink, bold = true))
            card.addView(NexusUi.text(this, message, 13f, Theme.ColorToken.ink2).apply {
                setPadding(0, dp(8), 0, 0); setLineSpacing(dp(3).toFloat(), 1f)
            })
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
            return
        }
        tracks.forEachIndexed { index, track ->
            content.addView(trackRow(track), LinearLayout.LayoutParams(-1, dp(76)).apply {
                topMargin = if (index == 0) dp(18) else dp(4)
            })
        }
    }

    private fun trackRow(track: LibraryTrack): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Drawables.rounded(this@LibraryActivity, Theme.ColorToken.paperStock, 14f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = "Play ${track.title}"
            setOnClickListener { playTrack(track) }
        }
        val artwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Drawables.rounded(this@LibraryActivity, Theme.ColorToken.groove, 8f)
            contentDescription = "${track.title} artwork"
            track.thumbnailUrl?.let { load(it) }
        }
        row.addView(artwork, LinearLayout.LayoutParams(dp(56), dp(56)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        labels.addView(NexusUi.text(this, track.title, 14f, Theme.ColorToken.ink, bold = true, maxLines = 1))
        labels.addView(NexusUi.text(this, track.artist ?: "Unknown artist", 12f, Theme.ColorToken.ink2, maxLines = 1).apply {
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        row.addView(NexusUi.text(this, "›", 24f, Theme.ColorToken.ink2).apply {
            gravity = Gravity.CENTER; contentDescription = "Play track"
        }, LinearLayout.LayoutParams(dp(28), dp(48)))
        return row
    }

    private fun renderPlaylists() {
        val create = Widgets.copperButton(this, "Create playlist") { showPlaylistDialog(null) }
        content.addView(create, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(18) })
        val playlists = library.playlists()
        if (playlists.isEmpty()) {
            val card = Widgets.paperCard(this, 20)
            card.addView(NexusUi.text(this, "Your playlists start here", 19f, Theme.ColorToken.ink, bold = true))
            card.addView(NexusUi.text(this, "Create a playlist to collect tracks around a mood, artist or moment.", 13f, Theme.ColorToken.ink2).apply {
                setPadding(0, dp(8), 0, 0); setLineSpacing(dp(3).toFloat(), 1f)
            })
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            return
        }
        playlists.forEachIndexed { index, playlist ->
            content.addView(playlistRow(playlist), LinearLayout.LayoutParams(-1, dp(72)).apply {
                topMargin = if (index == 0) dp(16) else dp(6)
            })
        }
    }

    private fun playlistRow(playlist: PlaylistSummary): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Drawables.rounded(this@LibraryActivity, Theme.ColorToken.paperStock, 14f)
            setPadding(dp(14), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { openedPlaylistId = playlist.id; openedPlaylistName = playlist.name; render() }
            setOnLongClickListener { showPlaylistActions(playlist); true }
        }
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        details.addView(NexusUi.text(this, playlist.name, 15f, Theme.ColorToken.ink, bold = true, maxLines = 1))
        details.addView(NexusUi.text(this, "${playlist.trackCount} tracks", 12f, Theme.ColorToken.ink2).apply {
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Widgets.iconButton(this, "Playlist options", "···") { showPlaylistActions(playlist) })
        return row
    }

    private fun showPlaylistActions(playlist: PlaylistSummary) {
        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(arrayOf("Open", "Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> { openedPlaylistId = playlist.id; openedPlaylistName = playlist.name; render() }
                    1 -> showPlaylistDialog(playlist)
                    2 -> AlertDialog.Builder(this)
                        .setTitle("Delete playlist?")
                        .setMessage("This removes the playlist and its saved list, but does not delete tracks from Liked or Saved.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            library.deletePlaylist(playlist.id)
                            render()
                        }.show()
                }
            }.show()
    }

    private fun showPlaylistDialog(existing: PlaylistSummary?) {
        val input = Widgets.paperInput(this, "Playlist name").apply {
            setSingleLine(true)
            if (existing != null) setText(existing.name)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New playlist" else "Rename playlist")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (existing == null) "Create" else "Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank() || name.length > 80) {
                    input.error = "Enter a name up to 80 characters."
                    return@setOnClickListener
                }
                try {
                    if (existing == null) library.createPlaylist(name)
                    else library.renamePlaylist(existing.id, name)
                    dialog.dismiss()
                    render()
                } catch (error: Exception) {
                    input.error = error.message ?: "That playlist name could not be used."
                }
            }
        }
        dialog.show()
    }

    private fun playTrack(track: LibraryTrack) {
        if (resolveJob?.isActive == true) return
        Toast.makeText(this, "Preparing ${track.title}", Toast.LENGTH_SHORT).show()
        resolveJob = activityScope.launch {
            try {
                val audio = catalog.resolveAudio(track.url)
                if (playback?.play(audio) != true) {
                    Toast.makeText(this@LibraryActivity, "Player unavailable. Try again.", Toast.LENGTH_LONG).show()
                } else {
                    getSharedPreferences("nexus_home", MODE_PRIVATE).edit()
                        .putString("last_track_url", track.url)
                        .putString("last_track_title", track.title)
                        .putString("last_track_artist", track.artist)
                        .putString("last_track_thumbnail", track.thumbnailUrl)
                        .putLong("last_track_duration", track.durationSeconds ?: -1L)
                        .apply()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(this@LibraryActivity,
                    error.message ?: "Couldn't prepare this track. Try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun navigate(destination: Class<out Activity>) {
        startActivity(Intent(this, destination).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        overridePendingTransition(R.anim.nexus_page_enter, R.anim.nexus_page_exit)
    }

    private fun applySystemBarInsets(view: View) {
        view.setOnApplyWindowInsetsListener { target, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                target.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                target.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        view.requestApplyInsets()
    }

    override fun onDestroy() {
        resolveJob?.cancel()
        playback?.close()
        playback = null
        activityScope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

class ProfileActivity : NexusPlaceholderActivity() {
    override val selectedTab = NexusUi.NavigationTab.YOU
    override val pageTitle = "Your space"
    override val eyebrow = "Nexus account"
    override val emptyTitle = "Your profile is ready when you are"
    override val emptyMessage = "Account details and preferences will live here. Sign-in and cloud backup are intentionally deferred."
}
