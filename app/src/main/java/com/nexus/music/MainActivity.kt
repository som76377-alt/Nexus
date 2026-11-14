package com.nexus.music

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
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
import com.nexus.music.library.LocalLibraryStore
import com.nexus.music.ui.LibraryView
import com.nexus.music.ui.NexusUi
import com.nexus.music.ui.NowPlayingView
import com.nexus.music.ui.NexusDesignSystem
import com.nexus.music.ui.SearchPanel
import com.nexus.music.ui.YouView
import com.nexus.music.catalog.ResolvedAudio
import com.nexus.music.catalog.NexusCatalog
import com.nexus.music.catalog.SearchSessionManager
import com.nexus.music.catalog.RepositorySearchSource
import com.nexus.music.catalog.YoutubeSearchScope
import com.nexus.music.playback.PlaybackConnection
import com.nexus.music.playback.PlaybackSnapshot
import com.nexus.music.playback.AudioCacheStore
import com.nexus.music.playback.CachedAudioResolver
import com.nexus.music.playback.PersistentAudioCacheLookup
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private enum class SearchSort { RELEVANCE, POPULAR, TITLE, DURATION }
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { NexusCatalog.repository(this) }
    private val searchSessionManager by lazy {
        SearchSessionManager(activityScope, listOf(RepositorySearchSource(repository)))
    }
    private val libraryStore by lazy { LocalLibraryStore(this) }
    private val updateChecker by lazy { GitHubReleaseUpdater() }
    private val progressHandler = Handler(Looper.getMainLooper())

    private lateinit var searchPanel: SearchPanel
    private lateinit var homeShelvesContainer: LinearLayout
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
    private lateinit var nowPlayingView: NowPlayingView
    private lateinit var mainScroll: ScrollView
    private lateinit var homeContent: LinearLayout
    private lateinit var bottomNavigationHost: LinearLayout

    private var playbackConnection: PlaybackConnection? = null
    private var updateCheckJob: Job? = null
    private var downloadJob: Job? = null
    private var pendingUpdate: NexusUpdate? = null
    private var requiresCleanInstall = false
    private var selectedScope = YoutubeSearchScope.MUSIC_SONGS
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var isResolving = false
    private var youRenderGeneration = 0L
    private var isSeeking = false
    private var isActivityStarted = false
    private var lastPlaybackSnapshot = PlaybackSnapshot(connected = false)
    private var lastSearchResults: List<CatalogTrack> = emptyList()
    private var currentSort = SearchSort.RELEVANCE
    private var backInvokedCallback: android.window.OnBackInvokedCallback? = null

    private val screenBackground = NexusDesignSystem.Palette.archiveIvory
    private val surface = NexusDesignSystem.Palette.sleeveWhite
    private val surfaceRaised = NexusDesignSystem.Palette.sleeveWhite
    private val accent = NexusDesignSystem.Palette.terracotta
    private val primaryText = NexusDesignSystem.Palette.carbonInk
    private val secondaryText = Color.rgb(104, 94, 82)

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
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = FrameLayout(this).apply { setBackgroundColor(screenBackground) }
        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(screenBackground)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        mainScroll = scroll
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(32))
        }
        homeContent = content

        content.addView(com.nexus.music.ui.HomeDiscoveryView(
            context = this,
            onExplore = { query ->
                searchPanel.queryInput.setText(query)
                selectedScope = YoutubeSearchScope.MUSIC_SONGS
                searchPanel.setSelectedScope(selectedScope)
                performSearch()
            },
            onProviderLab = {
                startActivity(Intent(this, com.nexus.music.diagnostics.ProviderLabActivity::class.java))
            }
        ), verticalParams(18))

        homeShelvesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(homeShelvesContainer, verticalParams(18))

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
            setTextColor(Color.rgb(14, 20, 12))
            typeface = Typeface.DEFAULT_BOLD
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
        val sortButton = NexusUi.actionButton(this, "Sort results", primary = false).apply {
            setOnClickListener { showSearchSortMenu(this) }
        }
        content.addView(sortButton, verticalParams(8))
        content.addView(searchPanel, verticalParams(18))

        scroll.addView(content)
        base.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        base.addView(buildPlayerPanel(), LinearLayout.LayoutParams(-1, -2))
        bottomNavigationHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bottomNavigationHost.addView(buildBottomNavigation(), LinearLayout.LayoutParams(-1, dp(62)))
        base.addView(bottomNavigationHost, LinearLayout.LayoutParams(-1, dp(62)))
        root.addView(base, FrameLayout.LayoutParams(-1, -1))
        nowPlayingView = NowPlayingView(
            context = this,
            onClose = { nowPlayingView.visibility = View.GONE },
            onToggle = { playbackConnection?.togglePlayback() },
            onSeek = { playbackConnection?.seekTo(it) }
        ).apply {
            visibility = View.GONE
            render(lastPlaybackSnapshot)
        }
        root.addView(nowPlayingView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        if (intent.getBooleanExtra(EXTRA_OPEN_YOU, false)) showYou() else if (intent.getBooleanExtra(EXTRA_OPEN_LIBRARY, false)) showLibrary()
        loadHomeShelves()
        registerBackNavigation()
        applySystemBarInsets(base)
        connectPlaybackService()
        NexusUpdateScheduler.disableBackgroundUpdates(this)
    }

    private fun registerBackNavigation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = android.window.OnBackInvokedCallback {
                if (::nowPlayingView.isInitialized && nowPlayingView.visibility == View.VISIBLE) {
                    nowPlayingView.visibility = View.GONE
                } else if (::mainScroll.isInitialized && mainScroll.childCount > 0 && mainScroll.getChildAt(0) !== homeContent) {
                    showHome()
                } else {
                    finishAffinity()
                }
            }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
            backInvokedCallback = callback
        }
    }

    private fun buildBottomNavigation(selected: NexusUi.NavigationTab = NexusUi.NavigationTab.HOME): LinearLayout =
        NexusUi.bottomNavigation(
            context = this,
            selected = selected,
            onHome = ::showHome,
            onLibrary = ::showLibrary,
            onRoom = ::openRoomPage,
            onYou = ::showYou
        )

    private fun showHome() {
        youRenderGeneration++
        if (!::mainScroll.isInitialized) return
        mainScroll.removeAllViews()
        mainScroll.addView(homeContent)
        bottomNavigationHost.removeAllViews()
        bottomNavigationHost.addView(buildBottomNavigation(NexusUi.NavigationTab.HOME), LinearLayout.LayoutParams(-1, dp(62)))
    }

    private fun showLibrary() {
        youRenderGeneration++
        if (!::mainScroll.isInitialized) return
        mainScroll.removeAllViews()
        mainScroll.addView(
            LibraryView(
                context = this,
                saved = libraryStore.savedTracks(),
                recent = libraryStore.recentTracks(),
                onPlay = ::startResolvingTrack,
                onRemove = { track -> libraryStore.remove(track); showLibrary() },
                onBack = ::showHome
            )
        )
        bottomNavigationHost.removeAllViews()
        bottomNavigationHost.addView(buildBottomNavigation(NexusUi.NavigationTab.LIBRARY), LinearLayout.LayoutParams(-1, dp(62)))
    }

    private fun showYou() {
        if (!::mainScroll.isInitialized) return
        val generation = ++youRenderGeneration
        activityScope.launch {
            val usedBytes = withContext(Dispatchers.IO) {
                AudioCacheStore.usedBytes(this@MainActivity)
            }
            if (generation != youRenderGeneration || isFinishing || isDestroyed) return@launch
            mainScroll.removeAllViews()
            mainScroll.addView(
                YouView(
                    context = this@MainActivity,
                    savedCount = libraryStore.savedTracks().size,
                    recentCount = libraryStore.recentTracks().size,
                    onLibrary = ::showLibrary,
                    onAbout = ::showAboutNexus,
                    cacheUsedBytes = usedBytes,
                    cacheMaxBytes = AudioCacheStore.maxBytes(this@MainActivity),
                    onClearCache = {
                        val requestedGeneration = youRenderGeneration
                        activityScope.launch(Dispatchers.IO) {
                            PersistentAudioCacheLookup(this@MainActivity).clear()
                            withContext(Dispatchers.Main) {
                                if (requestedGeneration == youRenderGeneration) showYou()
                            }
                        }
                    },
                    onCacheLimitChanged = { bytes ->
                        val requestedGeneration = youRenderGeneration
                        activityScope.launch(Dispatchers.IO) {
                            AudioCacheStore.setMaxBytes(this@MainActivity, bytes)
                            withContext(Dispatchers.Main) {
                                if (requestedGeneration == youRenderGeneration) showYou()
                            }
                        }
                    }
                )
            )
            bottomNavigationHost.removeAllViews()
            bottomNavigationHost.addView(
                buildBottomNavigation(NexusUi.NavigationTab.YOU),
                LinearLayout.LayoutParams(-1, dp(62))
            )
        }
    }

    private fun showAboutNexus() {
        AlertDialog.Builder(this)
            .setTitle("Nexus")
            .setMessage(
                "The art of listening.\n\nDiscover music, keep a personal collection, " +
                    "listen in the background and share listening rooms.\n\n" +
                    "Official project and releases: github.com/som76377-alt/Nexus"
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun openRoomPage() {
        youRenderGeneration++
        startActivity(
            Intent(this, com.nexus.music.rooms.ListeningRoomActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
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
            } catch (_: Exception) {
                updateStatusText.text =
                    "Update check failed · Retry"
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
            } catch (_: InstallPermissionRequiredException) {
                updateStatusText.text = "Allow installs from Nexus in Android settings, then retry."
                updateButton.text = "Download"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Android install permission needed")
                    .setMessage("Allow Nexus to install updates in Android settings, then retry.")
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
                updateStatusText.text = "Download failed · Retry"
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
        player.container.setOnClickListener {
            nowPlayingView.setArtwork(lastPlaybackSnapshot.artworkUrl)
            nowPlayingView.render(lastPlaybackSnapshot)
            nowPlayingView.visibility = View.VISIBLE
        }
        playbackProgress = player.progress
        playerArtwork = player.artwork
        playerTitle = player.title
        playerSubtitle = player.subtitle
        playPauseButton = player.playPause
        return player.container
    }

    private fun connectPlaybackService() {
        playbackConnection = PlaybackConnection(
            context = this,
            onReady = {
                updatePlaybackUi(playbackConnection?.snapshot() ?: PlaybackSnapshot(connected = false))
            },
            onStateChanged = ::updatePlaybackUi,
            onError = {
                playerSubtitle.text = "Playback unavailable"
                Toast.makeText(this, "Playback could not start. Please retry.", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun updatePlaybackUi(snapshot: PlaybackSnapshot) {
        lastPlaybackSnapshot = snapshot
        if (::nowPlayingView.isInitialized) {
            nowPlayingView.setArtwork(snapshot.artworkUrl)
            if (nowPlayingView.visibility == View.VISIBLE) nowPlayingView.render(snapshot)
        }
        scheduleProgressTicker(snapshot)
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

    private fun performSearch() {
        val query = searchPanel.queryInput.text.toString().trim()
        if (query.isEmpty()) {
            searchGeneration++
            searchJob?.cancel()
            searchJob = null
            searchSessionManager.cancelActive()
            searchPanel.queryInput.error = null
            searchPanel.statusText.text = ""
            searchPanel.statusText.visibility = View.GONE
            searchPanel.statusText.setOnClickListener(null)
            searchPanel.resultsContainer.removeAllViews()
            return
        }

        // A newer query or scope always supersedes the previous request.
        searchJob?.cancel()
        searchPanel.queryInput.error = null
        val requestGeneration = ++searchGeneration
        val requestScope = selectedScope
        searchPanel.statusText.text = "Searching…"
        searchPanel.statusText.visibility = View.VISIBLE
        searchPanel.statusText.setOnClickListener(null)
        searchPanel.resultsContainer.removeAllViews()

        val session = searchSessionManager.start(query, requestScope)
        searchJob = activityScope.launch {
            try {
                session.state.collect { snapshot ->
                    if (requestGeneration != searchGeneration) return@collect
                    lastSearchResults = snapshot.items
                    renderSearchResults()
                    when {
                        snapshot.isLoadingFirstPage -> {
                            searchPanel.statusText.text = "Searching…"
                            searchPanel.statusText.visibility = View.VISIBLE
                            searchPanel.statusText.setOnClickListener(null)
                        }
                        snapshot.allSourcesFailed -> {
                            searchPanel.statusText.text = "Search unavailable · Retry"
                            searchPanel.statusText.visibility = View.VISIBLE
                            searchPanel.statusText.setOnClickListener { performSearch() }
                        }
                        snapshot.items.isEmpty() -> {
                            searchPanel.statusText.text = "No results"
                            searchPanel.statusText.visibility = View.VISIBLE
                            searchPanel.statusText.setOnClickListener(null)
                        }
                        else -> {
                            searchPanel.statusText.text = ""
                            searchPanel.statusText.visibility = View.GONE
                            searchPanel.statusText.setOnClickListener(null)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } finally {
                if (requestGeneration == searchGeneration && !isDestroyed) {
                    searchJob = null
                }
            }
        }
    }

    private fun loadHomeShelves() {
        val shelves = listOf(
            "Popular Indian music" to "popular Indian songs",
            "Independent voices" to "Indian independent music",
            "Ghazal archive" to "ghazal classics",
            "Instrumental focus" to "Indian instrumental music"
        )
        homeShelvesContainer.removeAllViews()
        activityScope.launch {
            shelves.forEach { (title, query) ->
                try {
                    val tracks = repository.search(query, YoutubeSearchScope.MUSIC_SONGS)
                        .distinctBy { it.url }
                        .sortedByDescending { it.viewCount ?: -1L }
                        .take(8)
                    if (tracks.isNotEmpty() && !isFinishing && !isDestroyed) {
                        homeShelvesContainer.addView(buildHomeShelf(title, tracks), verticalParams(20))
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A single unavailable discovery query must not hide other Home content.
                }
            }
        }
    }

    private fun buildHomeShelf(title: String, tracks: List<CatalogTrack>): LinearLayout {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        section.addView(text(title, 22f, primaryText, bold = true))
        section.addView(text("Sorted by available view count", 11f, secondaryText).apply {
            setPadding(0, dp(3), 0, dp(10))
        })
        val horizontal = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tracks.forEach { track ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener { startResolvingTrack(track) }
                contentDescription = "Play ${track.title}"
            }
            val art = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(surfaceRaised, 8)
                contentDescription = "Artwork for ${track.title}"
            }
            track.thumbnailUrl?.let { art.load(it) }
            tile.addView(art, LinearLayout.LayoutParams(dp(142), dp(142)))
            tile.addView(text(track.title.ifBlank { "Untitled" }, 13f, primaryText, bold = true, maxLines = 2).apply {
                setPadding(0, dp(7), 0, dp(2))
            }, LinearLayout.LayoutParams(dp(142), -2))
            tile.addView(text(track.artist ?: "Unknown artist", 11f, secondaryText, maxLines = 1))
            row.addView(tile, LinearLayout.LayoutParams(dp(142), -2).apply {
                marginEnd = dp(12)
            })
        }
        horizontal.addView(row)
        section.addView(horizontal)
        return section
    }

    private fun showSearchSortMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 0, 0, "YouTube relevance")
            menu.add(0, 1, 1, "Most viewed")
            menu.add(0, 2, 2, "Title A–Z")
            menu.add(0, 3, 3, "Longest first")
            setOnMenuItemClickListener { item ->
                currentSort = SearchSort.values().getOrElse(item.itemId) { SearchSort.RELEVANCE }
                renderSearchResults()
                true
            }
            show()
        }
    }

    private fun renderSearchResults() {
        searchPanel.resultsContainer.removeAllViews()
        val sorted = when (currentSort) {
            SearchSort.RELEVANCE -> lastSearchResults
            SearchSort.POPULAR -> lastSearchResults.sortedByDescending { it.viewCount ?: -1L }
            SearchSort.TITLE -> lastSearchResults.sortedBy { it.title.lowercase() }
            SearchSort.DURATION -> lastSearchResults.sortedByDescending { it.durationSeconds ?: -1L }
        }
        sorted.forEachIndexed { index, track ->
            searchPanel.resultsContainer.addView(resultCard(track), verticalParams(if (index == 0) 0 else 10))
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
            setOnClickListener { startResolvingTrack(track) }
        }
        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(surfaceRaised, 10)
            contentDescription = "Track thumbnail"
        }
        track.thumbnailUrl?.let { thumbnail.load(it) }
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
        val saved = libraryStore.isSaved(track)
        card.addView(TextView(this).apply {
            text = if (saved) "✓" else "＋"
            textSize = 21f
            setTextColor(if (saved) NexusDesignSystem.Palette.deepVinylGreen else NexusDesignSystem.Palette.terracotta)
            gravity = Gravity.CENTER
            contentDescription = if (saved) "Saved to Library" else "Save to Library"
            setOnClickListener {
                if (libraryStore.isSaved(track)) {
                    libraryStore.remove(track)
                    Toast.makeText(this@MainActivity, "Removed from Library", Toast.LENGTH_SHORT).show()
                } else {
                    libraryStore.save(track)
                    Toast.makeText(this@MainActivity, "Saved to Library", Toast.LENGTH_SHORT).show()
                }
                renderSearchResults()
            }
        }, LinearLayout.LayoutParams(dp(38), dp(54)))
        return card
    }

    private fun startResolvingTrack(track: CatalogTrack) {
        if (isResolving) return
        isResolving = true
        playerSubtitle.text = "Preparing audio…"
        activityScope.launch {
            try {
                val audio = CachedAudioResolver(
                    PersistentAudioCacheLookup(this@MainActivity),
                    repository::resolveAudio
                ).resolve(track.url)
                showResolved(audio)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = "Could not prepare audio. Try another result or retry."
                playerSubtitle.text = "Couldn't prepare audio"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            } finally {
                isResolving = false
            }
        }
    }

    private fun showResolved(audio: ResolvedAudio) {
        playerTitle.text = audio.title
        playerSubtitle.text = "Sending audio to player…"
        playerArtwork.load(audio.thumbnailUrl?.takeIf(String::isNotBlank))
        if (::nowPlayingView.isInitialized) nowPlayingView.setArtwork(audio.thumbnailUrl)
        playPauseButton.isEnabled = true
        if (playbackConnection?.play(audio) != true) {
            playerSubtitle.text = "Player unavailable"
            Toast.makeText(this, "The player could not accept this track. Try selecting it again.", Toast.LENGTH_LONG).show()
            return
        }
        playerSubtitle.text = "Starting playback…"
        libraryStore.recordPlayed(
            CatalogTrack(
                url = audio.sourceUrl,
                title = audio.title,
                artist = audio.artist,
                thumbnailUrl = audio.thumbnailUrl,
                durationSeconds = audio.durationSeconds,
                viewCount = null
            )
        )
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
        typeface = if (bold) Typeface.create(NexusDesignSystem.Type.interfaceText(this@MainActivity), Typeface.BOLD)
            else NexusDesignSystem.Type.interfaceText(this@MainActivity)
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
        if (::nowPlayingView.isInitialized && nowPlayingView.visibility == View.VISIBLE) {
            nowPlayingView.visibility = View.GONE
        } else if (::mainScroll.isInitialized && mainScroll.childCount > 0 && mainScroll.getChildAt(0) !== homeContent) {
            showHome()
        } else {
            finishAffinity()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when {
            intent.getBooleanExtra(EXTRA_OPEN_YOU, false) -> showYou()
            intent.getBooleanExtra(EXTRA_OPEN_LIBRARY, false) -> showLibrary()
            else -> showHome()
        }
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
        searchSessionManager.cancelActive()
        progressHandler.removeCallbacks(progressTicker)
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
        const val EXTRA_OPEN_LIBRARY = "com.nexus.music.extra.OPEN_LIBRARY"
        const val EXTRA_OPEN_YOU = "com.nexus.music.extra.OPEN_YOU"
    }
}
