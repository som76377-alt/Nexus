package com.nexus.music

import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.animation.LinearInterpolator
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.PopupMenu
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.Player
import coil3.load
import com.nexus.music.catalog.CatalogTrack
import com.nexus.music.ui.NexusUi
import com.nexus.music.ui.Theme
import com.nexus.music.ui.Drawables
import com.nexus.music.ui.Type
import com.nexus.music.ui.PaperTexture
import com.nexus.music.ui.SearchPanel
import com.nexus.music.catalog.ResolvedAudio
import com.nexus.music.catalog.YoutubeCatalogRepository
import com.nexus.music.catalog.YoutubeSearchScope
import com.nexus.music.playback.PlaybackConnection
import com.nexus.music.playback.PlaybackSnapshot
import com.nexus.music.update.GitHubReleaseUpdater
import com.nexus.music.update.InstallPermissionRequiredException
import com.nexus.music.update.NexusUpdate
import com.nexus.music.update.NexusUpdateInstaller
import com.nexus.music.update.NexusUpdateScheduler
import com.nexus.music.update.UpdateCheckResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { YoutubeCatalogRepository() }
    private val updateChecker by lazy { GitHubReleaseUpdater() }
    private val progressHandler = Handler(Looper.getMainLooper())

    private lateinit var searchPanel: SearchPanel
    private lateinit var updateButton: Button
    private lateinit var updateStatusText: TextView
    private lateinit var updatePanel: LinearLayout
    private lateinit var updateProgressContainer: LinearLayout
    private lateinit var updateProgressText: TextView
    private lateinit var updateProgressBar: ProgressBar
    private lateinit var playerArtwork: ImageView
    private lateinit var playerTitle: TextView
    private lateinit var playerSubtitle: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var playbackProgress: SeekBar

    private var playbackConnection: PlaybackConnection? = null
    private var updateCheckJob: Job? = null
    private var downloadJob: Job? = null
    private var pendingUpdate: NexusUpdate? = null
    private var requiresCleanInstall = false
    private var selectedScope = YoutubeSearchScope.MUSIC_SONGS
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var isResolving = false
    private var isSeeking = false
    private var isActivityStarted = false
    private var lastPlaybackSnapshot = PlaybackSnapshot(connected = false)
    private var backInvokedCallback: android.window.OnBackInvokedCallback? = null
    private var miniVinylAnimator: ObjectAnimator? = null

    private val screenBackground = Theme.ColorToken.parchment
    private val surface = Theme.ColorToken.paperStock
    private val surfaceRaised = Theme.ColorToken.paperStock
    private val accent = Theme.ColorToken.copper
    private val primaryText = Theme.ColorToken.ink
    private val secondaryText = Theme.ColorToken.ink2

    private val progressTicker = object : Runnable {
        override fun run() {
            if (!isActivityStarted || isFinishing || isDestroyed) return
            playbackConnection?.let { updatePlaybackUi(it.snapshot()) }
        }
    }

    /** Position polling is needed only while audio is advancing and this screen is visible. */
    private fun scheduleProgressTicker(snapshot: PlaybackSnapshot) {
        progressHandler.removeCallbacks(progressTicker)
        if (isActivityStarted && snapshot.isPlaying && !isFinishing && !isDestroyed) {
            progressHandler.postDelayed(progressTicker, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = screenBackground
        window.navigationBarColor = screenBackground
        window.decorView.systemUiVisibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(screenBackground)
        }
        PaperTexture.prepare(this) { texture -> if (texture != null && !isDestroyed) root.background = texture }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(32))
        }
        val wordmark = text("Nexus", 32f, Theme.ColorToken.ink, bold = true).apply {
            typeface = Type.title(this@MainActivity)
            letterSpacing = -0.02f
        }
        content.addView(wordmark, verticalParams(0))
        val greeting = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "GOOD MORNING"
            in 12..16 -> "GOOD AFTERNOON"
            else -> "GOOD EVENING"
        }
        content.addView(text(greeting, 11f, Theme.ColorToken.mineral).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(24), 0, dp(6))
        })
        val displayName = getSharedPreferences("nexus_home", MODE_PRIVATE)
            .getString("display_name", "Listener")?.takeIf { it.isNotBlank() } ?: "Listener"
        content.addView(text(displayName, 32f, Theme.ColorToken.ink, bold = true).apply {
            typeface = Type.display(this@MainActivity)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        buildContinueCard()?.let { content.addView(it, verticalParams(22)) }

        updatePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        val updateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        updateStatusText = text("Checking for updates…", 12f, secondaryText, maxLines = 2)
        updateRow.addView(updateStatusText, LinearLayout.LayoutParams(0, -2, 1f))
        updateButton = Button(this).apply {
            text = "Download"
            textSize = 11f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setTextColor(Theme.ColorToken.paperStock)
            typeface = Type.medium(this@MainActivity)
            background = rounded(accent, 10)
            setPadding(dp(12), 0, dp(12), 0)
            visibility = View.GONE
            setOnClickListener {
                val update = pendingUpdate
                when {
                    requiresCleanInstall -> showCleanInstallInstructions()
                    update != null -> installUpdate(update)
                    else -> checkForUpdates()
                }
            }
        }
        updateRow.addView(updateButton, LinearLayout.LayoutParams(-2, dp(36)).apply {
            marginStart = dp(10)
        })
        updatePanel.addView(updateRow)

        updateProgressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        updateProgressText = text("Preparing download…", 11f, secondaryText).apply {
            setPadding(0, dp(8), 0, dp(3))
        }
        updateProgressContainer.addView(updateProgressText)
        updateProgressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(surfaceRaised)
        }
        updateProgressContainer.addView(updateProgressBar, LinearLayout.LayoutParams(-1, dp(9)))
        updatePanel.addView(updateProgressContainer)
        content.addView(updatePanel, verticalParams(14))
        searchPanel = SearchPanel(
            context = this,
            initialScope = selectedScope,
            onSearchRequested = ::performSearch,
            onScopeSelected = ::selectScope
        )
        content.addView(searchPanel, verticalParams(18))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildPlayerPanel(), LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            bottomMargin = dp(8)
        })
        root.addView(buildBottomNavigation(), LinearLayout.LayoutParams(-1, dp(68)).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            bottomMargin = dp(10)
        })
        setContentView(root)
        registerBackNavigation()
        applySystemBarInsets(root)
        connectPlaybackService()
        NexusUpdateScheduler.disableBackgroundUpdates(this)
    }

    private fun registerBackNavigation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = android.window.OnBackInvokedCallback { finishAffinity() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
            backInvokedCallback = callback
        }
    }

    private fun buildBottomNavigation(): LinearLayout =
        NexusUi.bottomNavigation(
            context = this,
            selected = NexusUi.NavigationTab.HOME,
            onHome = {},
            onLibrary = ::openLibraryPage,
            onRoom = ::openRoomPage,
            onYou = ::openProfilePage
        )

    private fun openLibraryPage() {
        startActivity(Intent(this, LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        overridePendingTransition(R.anim.nexus_page_enter, R.anim.nexus_page_exit)
    }

    private fun openProfilePage() {
        startActivity(Intent(this, ProfileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        overridePendingTransition(R.anim.nexus_page_enter, R.anim.nexus_page_exit)
    }

    private fun openRoomPage() {
        startActivity(
            Intent(this, com.nexus.music.rooms.ListeningRoomActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
        overridePendingTransition(R.anim.nexus_page_enter, R.anim.nexus_page_exit)
    }

    /**
     * Android 15+ enforces edge-to-edge for apps targeting API 35+.
     * Keep the background behind system bars while keeping interactive content inside them.
     */
    private fun applySystemBarInsets(root: LinearLayout) {
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val safeInsets = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                view.setPadding(
                    safeInsets.left,
                    safeInsets.top,
                    safeInsets.right,
                    safeInsets.bottom
                )
            } else {
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            insets
        }
        root.requestApplyInsets()
    }


    /** Checks the latest stable GitHub release every time Nexus is opened. */
    private fun checkForUpdates() {
        if (updateCheckJob?.isActive == true || downloadJob?.isActive == true) return

        pendingUpdate = null
        updatePanel.visibility = View.VISIBLE
        updateStatusText.text = "Checking for updates…"
        updateStatusText.setTextColor(secondaryText)
        updateButton.visibility = View.GONE
        updateProgressContainer.visibility = View.GONE

        updateCheckJob = activityScope.launch {
            try {
                when (val result = updateChecker.checkForUpdate(this@MainActivity)) {
                    is UpdateCheckResult.UnverifiedInstallation -> {
                        pendingUpdate = null
                        requiresCleanInstall = true
                        updateStatusText.text = result.message
                            ?: "Signing identity UNKNOWN · official update compatibility is not verified"
                        updateStatusText.setTextColor(Color.rgb(255, 190, 130))
                        updateButton.text = "Install official build"
                        updateButton.isEnabled = true
                        updateButton.visibility = View.VISIBLE
                    }

                    is UpdateCheckResult.UpToDate -> {
                        requiresCleanInstall = false
                        pendingUpdate = null
                        updateStatusText.text = "You're up to date · ${result.installedVersion}"
                        updateStatusText.setTextColor(accent)
                        updateButton.visibility = View.GONE
                        updatePanel.visibility = View.GONE
                    }

                    is UpdateCheckResult.Available -> {
                        requiresCleanInstall = false
                        pendingUpdate = result.update
                        updateStatusText.text = "Update available · ${result.update.versionName}"
                        updateStatusText.setTextColor(primaryText)
                        updateButton.text = "Download"
                        updateButton.isEnabled = true
                        updateButton.visibility = View.VISIBLE
                    }

                    is UpdateCheckResult.InstalledBuildAhead -> {
                        requiresCleanInstall = false
                        pendingUpdate = null
                        updateStatusText.text =
                            "Preview build · ${result.installedVersion} · latest public release ${result.latestReleaseVersion}"
                        updateStatusText.setTextColor(secondaryText)
                        updateButton.visibility = View.GONE
                        updatePanel.visibility = View.GONE
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateStatusText.text =
                    "Update check failed · ${error.message ?: "Check your connection and retry."}"
                updateStatusText.setTextColor(Color.rgb(255, 170, 120))
                updateButton.text = "Retry"
                updateButton.isEnabled = true
                updateButton.visibility = View.VISIBLE
            } finally {
                if (!isFinishing && !isDestroyed) updateCheckJob = null
            }
        }
    }

    private fun showCleanInstallInstructions() {
        AlertDialog.Builder(this)
            .setTitle("Install the official Nexus build")
            .setMessage(
                "This installation's signing identity is UNKNOWN to the updater; it is not verified against " +
                    "the official release certificate. Android may reject an in-place update. Open " +
                    "the official release page first; if Android refuses the update, back up anything " +
                    "important, then uninstall this copy and install the official build. Uninstalling " +
                    "can erase this device's saved anonymous room identity."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open official release") { _, _ ->
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/som76377-alt/Nexus/releases/latest")
                    )
                )
            }
            .show()
    }

    private fun installUpdate(update: NexusUpdate) {
        if (downloadJob?.isActive == true) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            updateStatusText.text = "Android needs permission to install Nexus updates."
            AlertDialog.Builder(this)
                .setTitle("Allow Nexus to install updates")
                .setMessage("Android needs you to allow installs from Nexus. This is a one-time Android setting.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .show()
            return
        }

        updateButton.isEnabled = false
        updateButton.text = "Downloading…"
        updateProgressContainer.visibility = View.VISIBLE
        updateProgressBar.isIndeterminate = false
        updateProgressBar.progress = 0
        updateProgressText.text = "Connecting to GitHub…"
        updateStatusText.text = "Downloading Nexus ${update.versionName}…"

        downloadJob = activityScope.launch {
            var installerOpened = false
            try {
                val cachedApk = withContext(Dispatchers.IO) {
                    runCatching {
                        updateChecker.findVerifiedCachedUpdate(this@MainActivity, update)
                    }.getOrNull()
                }
                val apk = cachedApk ?: updateChecker.downloadAndVerify(
                    this@MainActivity,
                    update
                ) { downloadedBytes, totalBytes ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            if (totalBytes > 0L) {
                                val percent = ((downloadedBytes * 100L) / totalBytes)
                                    .toInt().coerceIn(0, 100)
                                updateProgressBar.isIndeterminate = false
                                updateProgressBar.progress = percent
                                updateProgressText.text =
                                    "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)} · $percent%"
                            } else {
                                updateProgressBar.isIndeterminate = true
                                updateProgressText.text =
                                    "${formatBytes(downloadedBytes)} downloaded"
                            }
                        }
                    }
                }

                updateProgressBar.isIndeterminate = false
                updateProgressBar.progress = 100
                updateProgressText.text = if (cachedApk != null) {
                    "Previously downloaded APK verified."
                } else {
                    "Download complete · APK integrity and signing verified."
                }
                updateStatusText.text = "Verified · opening Android installer…"
                updateButton.text = "Opening installer…"

                NexusUpdateInstaller.install(this@MainActivity, apk)
                installerOpened = true
                updateStatusText.text = "Android installer opened · approve the update to finish."
                updateButton.text = "Install again"
                Toast.makeText(
                    this@MainActivity,
                    "Approve the update in Android's installer to finish.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: InstallPermissionRequiredException) {
                updateStatusText.text = "Allow installs from Nexus in Android settings, then retry."
                updateButton.text = "Download"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Android install permission needed")
                    .setMessage(error.message)
                    .setPositiveButton("Open settings") { _, _ ->
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } catch (error: Exception) {
                updateStatusText.text = "Download failed · ${error.message ?: "Please try again."}"
                updateProgressText.text = "The update was not installed. Tap Retry to try again."
                updateButton.text = "Retry download"
                updateButton.visibility = View.VISIBLE
            } finally {
                if (!isFinishing && !isDestroyed) {
                    downloadJob = null
                    updateButton.isEnabled = true
                    if (installerOpened) {
                        updateButton.text = "Install again"
                        updateButton.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun buildPlayerPanel(): LinearLayout {
        val player = NexusUi.miniPlayer(
            context = this,
            onTogglePlayback = { playbackConnection?.togglePlayback() },
            onSeekStarted = { isSeeking = true },
            onSeekChanged = { isSeeking = true },
            onSeekFinished = { progress ->
                val duration = lastPlaybackSnapshot.durationMs
                if (duration > 0L) {
                    val target = duration * progress.toLong() / playbackProgress.max
                    playbackConnection?.seekTo(target)
                }
                isSeeking = false
            }
        )
        playbackProgress = player.progress
        playerArtwork = player.artwork
        playerTitle = player.title
        playerSubtitle = player.subtitle
        playPauseButton = player.playPause
        player.container.isClickable = true
        player.container.setOnClickListener { openNowPlaying() }
        playerArtwork.setOnClickListener { openNowPlaying() }
        playerTitle.setOnClickListener { openNowPlaying() }
        playerSubtitle.setOnClickListener { openNowPlaying() }
        return player.container
    }

    private fun openNowPlaying() {
        stopMiniVinylAnimation()
        startActivity(Intent(this, NowPlayingActivity::class.java))
        overridePendingTransition(R.anim.nexus_player_enter, R.anim.nexus_player_exit)
    }

    private fun updateMiniVinylAnimation(snapshot: PlaybackSnapshot) {
        if (!::playerArtwork.isInitialized) return
        if (isActivityStarted && snapshot.isPlaying) {
            if (miniVinylAnimator?.isStarted != true) {
                miniVinylAnimator?.cancel()
                miniVinylAnimator = ObjectAnimator.ofFloat(playerArtwork, View.ROTATION, 0f, 360f).apply {
                    duration = 3_600L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                }
            }
        } else {
            stopMiniVinylAnimation()
        }
    }

    private fun stopMiniVinylAnimation() {
        miniVinylAnimator?.cancel()
        miniVinylAnimator = null
        if (::playerArtwork.isInitialized) playerArtwork.rotation = 0f
    }

    private fun connectPlaybackService() {
        playbackConnection = PlaybackConnection(
            context = this,
            onReady = {
                updatePlaybackUi(playbackConnection?.snapshot() ?: PlaybackSnapshot(connected = false))
            },
            onStateChanged = ::updatePlaybackUi,
            onError = { message ->
                playerSubtitle.text = message
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun updatePlaybackUi(snapshot: PlaybackSnapshot) {
        lastPlaybackSnapshot = snapshot
        scheduleProgressTicker(snapshot)
        updateMiniVinylAnimation(snapshot)
        if (!snapshot.connected) {
            playerSubtitle.text = "Connecting to playback service…"
            playPauseButton.isEnabled = false
            return
        }

        if (snapshot.title.isNotBlank()) {
            playerTitle.text = snapshot.title
            playerSubtitle.text = when (snapshot.playbackState) {
                Player.STATE_BUFFERING -> "Loading audio…"
                Player.STATE_READY -> if (snapshot.playWhenReady) {
                    "Playing" + snapshot.artist.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                } else {
                    "Paused" + snapshot.artist.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                }
                Player.STATE_ENDED -> "Finished"
                else -> snapshot.artist.ifBlank { "Ready" }
            }
            playPauseButton.isEnabled = true
        } else {
            playerSubtitle.text = "Ready · choose a result to listen"
            playPauseButton.isEnabled = false
        }

        NexusUi.setPlaying(this, playPauseButton, snapshot.playWhenReady)
        if (!isSeeking) {
            playbackProgress.isEnabled = snapshot.durationMs > 0L
            playbackProgress.progress = if (snapshot.durationMs > 0L) {
                ((snapshot.positionMs.toDouble() / snapshot.durationMs.toDouble()) * playbackProgress.max)
                    .toInt().coerceIn(0, playbackProgress.max)
            } else 0
        }
    }

    private fun selectScope(scope: YoutubeSearchScope) {
        if (selectedScope == scope) return
        selectedScope = scope
        searchPanel.setSelectedScope(scope)
        if (searchPanel.queryInput.text.toString().isNotBlank()) performSearch()
    }

    private fun buildContinueCard(): LinearLayout? {
        val prefs = getSharedPreferences("nexus_home", MODE_PRIVATE)
        val url = prefs.getString("last_track_url", null)?.takeIf { it.isNotBlank() } ?: return null
        val title = prefs.getString("last_track_title", "Untitled") ?: "Untitled"
        val artist = prefs.getString("last_track_artist", "Unknown artist")
        val thumbnailUrl = prefs.getString("last_track_thumbnail", null)
        val duration = prefs.getLong("last_track_duration", -1L).takeIf { it >= 0L }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Theme.ColorToken.paperStock, 18)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            Drawables.raised(this, 1f)
        }
        card.addView(text("PICK UP WHERE YOU LEFT OFF", 10f, Theme.ColorToken.mineral).apply {
            letterSpacing = 0.08f
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Theme.ColorToken.parchment, 10)
            contentDescription = "Last played artwork"
        }
        thumbnailUrl?.let { art.load(it) }
        row.addView(art, LinearLayout.LayoutParams(dp(48), dp(48)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        labels.addView(text(title, 14f, Theme.ColorToken.ink, bold = true, maxLines = 1))
        labels.addView(text(artist ?: "Unknown artist", 12f, Theme.ColorToken.ink2, maxLines = 1).apply {
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val play = TextView(this).apply {
            text = "Play"
            textSize = 12f
            typeface = Type.medium(this@MainActivity)
            gravity = Gravity.CENTER
            setTextColor(Theme.ColorToken.paperStock)
            background = rounded(Theme.ColorToken.mineral, 14)
            minWidth = dp(56)
            minHeight = dp(44)
            contentDescription = "Play last track"
            setOnClickListener {
                startResolvingTrack(CatalogTrack(url, title, artist, thumbnailUrl, duration, null))
            }
        }
        row.addView(play)
        card.addView(row)
        return card
    }

    private fun showSearchSkeletons() {
        searchPanel.statusText.text = "Searching…"
        searchPanel.statusText.visibility = View.VISIBLE
        searchPanel.statusText.setOnClickListener(null)
        searchPanel.resultsContainer.removeAllViews()
        repeat(4) { index ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val art = View(this).apply {
                background = rounded(Theme.ColorToken.ink3, 10)
                alpha = 0.22f
            }
            row.addView(art, LinearLayout.LayoutParams(dp(56), dp(56)))
            val lines = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            lines.addView(View(this).apply {
                background = rounded(Theme.ColorToken.ink3, 4)
                alpha = 0.22f
            }, LinearLayout.LayoutParams(dp(if (index % 2 == 0) 150 else 120), dp(10)))
            lines.addView(View(this).apply {
                background = rounded(Theme.ColorToken.ink3, 4)
                alpha = 0.15f
            }, LinearLayout.LayoutParams(dp(90), dp(8)).apply { topMargin = dp(8) })
            row.addView(lines, LinearLayout.LayoutParams(0, -2, 1f))
            searchPanel.resultsContainer.addView(row, LinearLayout.LayoutParams(-1, dp(64)))
        }
    }

    private fun performSearch() {
        val query = searchPanel.queryInput.text.toString().trim()
        if (query.isEmpty()) {
            searchGeneration++
            searchJob?.cancel()
            searchJob = null
            searchPanel.queryInput.error = null
            searchPanel.statusText.text = ""
            searchPanel.statusText.visibility = View.GONE
            searchPanel.statusText.setOnClickListener(null)
            searchPanel.resultsContainer.removeAllViews()
            return
        }

        searchJob?.cancel()
        searchPanel.queryInput.error = null
        val requestGeneration = ++searchGeneration
        val requestScope = selectedScope
        showSearchSkeletons()

        searchJob = activityScope.launch {
            try {
                val results = repository.search(query, requestScope)
                if (requestGeneration != searchGeneration) return@launch
                searchPanel.resultsContainer.removeAllViews()
                searchPanel.statusText.setOnClickListener(null)
                if (results.isEmpty()) {
                    searchPanel.statusText.text = "No tracks found · Clear search"
                    searchPanel.statusText.visibility = View.VISIBLE
                    searchPanel.statusText.setOnClickListener {
                        searchPanel.queryInput.text.clear()
                        performSearch()
                    }
                } else {
                    searchPanel.statusText.visibility = View.GONE
                    results.forEachIndexed { index, track ->
                        searchPanel.resultsContainer.addView(
                            resultRow(track),
                            LinearLayout.LayoutParams(-1, dp(64)).apply {
                                if (index > 0) topMargin = dp(2)
                            }
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration == searchGeneration) {
                    searchPanel.resultsContainer.removeAllViews()
                    searchPanel.statusText.text = "Search failed · Tap to retry"
                    searchPanel.statusText.visibility = View.VISIBLE
                    searchPanel.statusText.setOnClickListener { performSearch() }
                }
            } finally {
                if (requestGeneration == searchGeneration && !isDestroyed) searchJob = null
            }
        }
    }

    private fun resultRow(track: CatalogTrack): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            isClickable = true
            isFocusable = true
            contentDescription = "Play ${track.title} by ${track.artist ?: "Unknown artist"}"
            setOnClickListener { startResolvingTrack(track) }
        }
        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Theme.ColorToken.paperStock, 10)
            contentDescription = "Track artwork"
        }
        track.thumbnailUrl?.let { thumbnail.load(it) }
        row.addView(thumbnail, LinearLayout.LayoutParams(dp(56), dp(56)))
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        details.addView(text(track.title.ifBlank { "Untitled" }, 14f, Theme.ColorToken.ink, bold = true, maxLines = 1))
        details.addView(text(track.artist ?: "Unknown artist", 12f, Theme.ColorToken.ink2, maxLines = 1).apply {
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        val duration = track.durationSeconds?.takeIf { it >= 0L }?.let(CatalogFormatting::duration).orEmpty()
        if (duration.isNotBlank()) {
            row.addView(text(duration, 11f, Theme.ColorToken.ink3).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(2), 0)
            })
        }
        val overflow = TextView(this).apply {
            text = "•••"
            textSize = 18f
            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(48)
            contentDescription = "More options for ${track.title}"
            setOnClickListener { anchor ->
                PopupMenu(this@MainActivity, anchor).apply {
                    menu.add("Play now").setOnMenuItemClickListener {
                        startResolvingTrack(track); true
                    }
                    menu.add("Copy link").setOnMenuItemClickListener {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("YouTube link", track.url))
                        Toast.makeText(this@MainActivity, "Link copied", Toast.LENGTH_SHORT).show()
                        true
                    }
                    show()
                }
            }
        }
        row.addView(overflow, LinearLayout.LayoutParams(dp(44), dp(48)))
        return row
    }

    private fun startResolvingTrack(track: CatalogTrack) {
        if (isResolving) return
        getSharedPreferences("nexus_home", MODE_PRIVATE).edit()
            .putString("last_track_url", track.url)
            .putString("last_track_title", track.title)
            .putString("last_track_artist", track.artist)
            .putString("last_track_thumbnail", track.thumbnailUrl)
            .putLong("last_track_duration", track.durationSeconds ?: -1L)
            .apply()
        isResolving = true
        playerSubtitle.text = "Preparing audio…"
        activityScope.launch {
            try {
                val audio = repository.resolveAudio(track.url)
                showResolved(audio)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                playerSubtitle.text = "Couldn't prepare audio"
                Toast.makeText(this@MainActivity, error.message ?: "Please choose another result.", Toast.LENGTH_LONG).show()
            } finally {
                isResolving = false
            }
        }
    }

    private fun showResolved(audio: ResolvedAudio) {
        playerTitle.text = audio.title
        playerSubtitle.text = "Sending audio to player…"
        playerArtwork.load(audio.thumbnailUrl?.takeIf(String::isNotBlank))
        playPauseButton.isEnabled = true
        if (playbackConnection?.play(audio) != true) {
            playerSubtitle.text = "Player unavailable"
            Toast.makeText(this, "The player could not accept this track. Try selecting it again.", Toast.LENGTH_LONG).show()
            return
        }
        playerSubtitle.text = "Starting playback…"
        Toast.makeText(this, "Starting playback", Toast.LENGTH_SHORT).show()
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        maxLines: Int = Int.MAX_VALUE
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Type.medium(this@MainActivity)
        this.maxLines = maxLines
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun verticalParams(top: Int) =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "${bytes} B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Use the platform back callback on Android 13 and newer.")
    override fun onBackPressed() {
        finishAffinity()
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true
        if (::updateStatusText.isInitialized) checkForUpdates()
        updatePlaybackUi(playbackConnection?.snapshot() ?: lastPlaybackSnapshot)
    }

    override fun onStop() {
        isActivityStarted = false
        progressHandler.removeCallbacks(progressTicker)
        stopMiniVinylAnimation()
        super.onStop()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
            backInvokedCallback = null
        }
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
        progressHandler.removeCallbacks(progressTicker)
        stopMiniVinylAnimation()
        playbackConnection?.close()
        playbackConnection = null
        updateCheckJob?.cancel()
        updateCheckJob = null
        downloadJob?.cancel()
        downloadJob = null
        activityScope.cancel()
        super.onDestroy()
    }

    companion object {
    }
}
