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
import com.nexus.music.ui.PlaylistHubView
import com.nexus.music.ui.NexusExperienceSettings
import com.nexus.music.ui.NexusHaptics
import com.nexus.music.recommendations.GenreBucket
import com.nexus.music.recommendations.RecommendationMixer
import com.nexus.music.recommendations.RecommendationHistoryStore
import com.nexus.music.recommendations.RecommendationCacheStore
import com.nexus.music.catalog.ResolvedAudio
import com.nexus.music.catalog.NexusCatalog
import com.nexus.music.catalog.YoutubeSearchScope
import com.nexus.music.playback.PlaybackConnection
import com.nexus.music.playback.PlaybackSnapshot
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.random.Random

class MainActivity : Activity() {
    private enum class SearchSort { RELEVANCE, POPULAR, TITLE, DURATION }
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { NexusCatalog.repository(this) }
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
    private lateinit var miniPlayerContainer: LinearLayout
    private lateinit var nowPlayingView: NowPlayingView
    private lateinit var mainScroll: ScrollView
    private lateinit var homeContent: LinearLayout
    private lateinit var searchContent: LinearLayout
    private lateinit var bottomNavigationHost: LinearLayout
    private val experienceSettings by lazy { NexusExperienceSettings(this) }
    private val recommendationHistory by lazy { RecommendationHistoryStore(this) }
    private val recommendationCache by lazy { RecommendationCacheStore(this) }
    private var recommendationsGeneration = 0L
    private var activeContentKey = "home"
    private var homeScrollPosition = 0
    private var searchScrollPosition = 0

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

    private val screenBackground = NexusDesignSystem.Palette.canvas
    private val surface = NexusDesignSystem.Palette.surface
    private val surfaceRaised = NexusDesignSystem.Palette.raised
    private val accent = NexusDesignSystem.Palette.muted
    private val primaryText = NexusDesignSystem.Palette.foreground
    private val secondaryText = NexusDesignSystem.Palette.muted

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
        window.decorView.systemUiVisibility = 0
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
            onSaved = { showLibrary("Saved", libraryStore.savedTracks(), allowRemove = true) },
            onHistory = { showLibrary("History", libraryStore.recentTracks(), allowRemove = false) },
            onPlaylists = ::showPlaylists,
            onBlend = ::showBlend,
            partnerConnected = false
        ), verticalParams(8))

        homeShelvesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(homeShelvesContainer, verticalParams(18))

        updatePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, NexusDesignSystem.Shape.large)
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
            setTextColor(primaryText)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(surfaceRaised, NexusDesignSystem.Shape.control)
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
        updateRow.addView(updateButton, LinearLayout.LayoutParams(-2, dp(48)).apply {
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
        searchPanel = SearchPanel(
            context = this,
            initialScope = selectedScope,
            onSearchRequested = ::performSearch,
            onScopeSelected = ::selectScope
        )
        searchContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(32))
            addView(text("Search", 30f, primaryText, bold = true), verticalParams(8))
        }
        val sortButton = NexusUi.actionButton(this, "Sort results", primary = false).apply {
            setOnClickListener { showSearchSortMenu(this) }
        }
        searchContent.addView(sortButton, verticalParams(8))
        searchContent.addView(searchPanel, verticalParams(12))

        scroll.addView(content)
        base.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        miniPlayerContainer = buildPlayerPanel().apply { visibility = View.GONE }
        base.addView(miniPlayerContainer, LinearLayout.LayoutParams(-1, -2))
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
        when {
            intent.getBooleanExtra(EXTRA_OPEN_YOU, false) -> showYou()
            intent.getBooleanExtra(EXTRA_OPEN_SEARCH, false) -> showSearch()
            intent.getBooleanExtra(EXTRA_OPEN_LIBRARY, false) -> showLibrary("Saved", libraryStore.savedTracks(), allowRemove = true)
            else -> showHome()
        }
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
            onSearch = ::showSearch,
            onRoom = ::openRoomPage,
            onYou = ::showYou
        )

    private fun displayContent(view: View, key: String) {
        if (mainScroll.childCount > 0) {
            when (activeContentKey) {
                "home" -> homeScrollPosition = mainScroll.scrollY
                "search" -> searchScrollPosition = mainScroll.scrollY
            }
        }
        activeContentKey = key
        mainScroll.removeAllViews()
        mainScroll.addView(view)
        val restorePosition = when (key) {
            "home" -> homeScrollPosition
            "search" -> searchScrollPosition
            else -> 0
        }
        mainScroll.post {
            if (activeContentKey == key) mainScroll.scrollTo(0, restorePosition)
        }
    }

    private fun renderBottomNavigation(selected: NexusUi.NavigationTab) {
        bottomNavigationHost.removeAllViews()
        bottomNavigationHost.addView(buildBottomNavigation(selected), LinearLayout.LayoutParams(-1, dp(62)))
    }

    private fun showHome() {
        youRenderGeneration++
        if (!::mainScroll.isInitialized) return
        displayContent(homeContent, "home")
        renderBottomNavigation(NexusUi.NavigationTab.HOME)
    }

    private fun showSearch() {
        youRenderGeneration++
        if (!::mainScroll.isInitialized) return
        displayContent(searchContent, "search")
        renderBottomNavigation(NexusUi.NavigationTab.SEARCH)
    }

    private fun showLibrary(
        title: String,
        tracks: List<CatalogTrack>,
        allowRemove: Boolean,
        onBack: () -> Unit = ::showHome
    ) {
        youRenderGeneration++
        val removeAction: ((CatalogTrack) -> Unit)? = if (allowRemove) ({ track ->
            when (title) {
                "Saved" -> libraryStore.remove(track)
                "Watch later" -> libraryStore.removeFromWatchLater(track)
                else -> libraryStore.removeFromPlaylist(track, title)
            }
            showLibrary(title, libraryStoreTracks(title), allowRemove, onBack)
        }) else null
        displayContent(
            LibraryView(
                context = this,
                title = title,
                tracks = tracks,
                onPlay = ::startResolvingTrack,
                onRemove = removeAction,
                onBack = onBack
            ),
            "library"
        )
        renderBottomNavigation(NexusUi.NavigationTab.HOME)
    }

    private fun libraryStoreTracks(title: String): List<CatalogTrack> = when (title) {
        "Saved" -> libraryStore.savedTracks()
        "History" -> libraryStore.recentTracks()
        "Watch later" -> libraryStore.watchLaterTracks()
        else -> libraryStore.playlistTracks(title)
    }

    private fun showPlaylists() {
        youRenderGeneration++
        val playlists = libraryStore.playlistNames().map { it to libraryStore.playlistTracks(it).size }
        displayContent(
            PlaylistHubView(
                context = this,
                watchLaterCount = libraryStore.watchLaterTracks().size,
                playlists = playlists,
                onOpen = ::showPlaylist,
                onCreate = ::createPlaylist,
                onBack = ::showHome
            ),
            "playlists"
        )
        renderBottomNavigation(NexusUi.NavigationTab.HOME)
    }

    private fun showPlaylist(name: String) {
        showLibrary(
            name,
            libraryStore.playlistTracks(name),
            allowRemove = true,
            onBack = ::showPlaylists
        )
    }

    private fun createPlaylist() = promptCreatePlaylist(null)

    private fun showBlend() {
        Toast.makeText(this, "Blend appears when a partner is connected.", Toast.LENGTH_SHORT).show()
    }

    private fun showYou() {
        if (!::mainScroll.isInitialized) return
        youRenderGeneration++
        val youContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(screenBackground)
        }
        youContent.addView(
            YouView(
                context = this,
                savedCount = libraryStore.savedTracks().size,
                historyCount = libraryStore.recentTracks().size,
                partnerStatus = "Not connected",
                roomStatus = "Open Rooms to connect",
                reduceEffects = experienceSettings.reduceEffects || experienceSettings.effectsShouldBeReduced(),
                hapticsEnabled = experienceSettings.hapticsEnabled,
                onReduceEffectsChanged = { experienceSettings.reduceEffects = it },
                onHapticsChanged = { experienceSettings.hapticsEnabled = it },
                onAbout = ::showAboutNexus
            ),
            LinearLayout.LayoutParams(-1, -2)
        )
        youContent.addView(updatePanel, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(22), dp(10), dp(22), dp(24))
        })
        displayContent(youContent, "you")
        renderBottomNavigation(NexusUi.NavigationTab.YOU)
    }

    private fun showAboutNexus() {
        AlertDialog.Builder(this)
            .setTitle("Nexus")
            .setMessage("Music discovery and listening rooms.\n\nOfficial project: github.com/som76377-alt/Nexus")
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
        if (::miniPlayerContainer.isInitialized) {
            miniPlayerContainer.visibility = if (shouldShowMiniPlayer(snapshot)) View.VISIBLE else View.GONE
        }
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
        searchPanel.statusText.text = ""
        searchPanel.statusText.visibility = View.GONE
        searchPanel.statusText.setOnClickListener(null)
        searchPanel.resultsContainer.removeAllViews()

        searchJob = activityScope.launch {
            try {
                val results = repository.search(query, requestScope)
                if (requestGeneration != searchGeneration) return@launch

                lastSearchResults = results
                renderSearchResults()
                if (results.isEmpty()) {
                    searchPanel.statusText.text = "No results"
                    searchPanel.statusText.visibility = View.VISIBLE
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration == searchGeneration) {
                    searchPanel.statusText.text = "Search unavailable · Retry"
                    searchPanel.statusText.visibility = View.VISIBLE
                    searchPanel.statusText.setOnClickListener { performSearch() }
                }
            } finally {
                if (requestGeneration == searchGeneration && !isDestroyed) {
                    searchJob = null
                }
            }
        }
    }

    private fun loadHomeShelves() {
        val generation = ++recommendationsGeneration
        val seed = Random.nextLong()
        val buckets = readGenreBuckets().shuffled(Random(seed)).take(6)
        homeShelvesContainer.removeAllViews()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text("Recommendations", 19f, primaryText, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(NexusUi.actionButton(this, "Reroll", primary = false).apply {
            setOnClickListener { loadHomeShelves() }
        }, LinearLayout.LayoutParams(-2, dp(42)))
        homeShelvesContainer.addView(header, verticalParams(12))
        val status = text("Finding a new mix…", 13f, secondaryText)
        homeShelvesContainer.addView(status, verticalParams(8))

        activityScope.launch {
            try {
                val semaphore = Semaphore(3)
                val genreResults = coroutineScope {
                    buckets.map { bucket ->
                        async {
                            semaphore.withPermit {
                                val cached = recommendationCache.get(bucket.id)
                                val tracks = cached ?: repository.search(bucket.query, YoutubeSearchScope.MUSIC_SONGS)
                                    .distinctBy { it.url }
                                    .take(40)
                                    .also { recommendationCache.put(bucket.id, it) }
                                bucket.id to tracks
                            }
                        }
                    }.awaitAll().toMap()
                }

                val recentArtists = libraryStore.recentTracks()
                    .mapNotNull { it.artist?.trim()?.takeIf(String::isNotBlank) }
                    .distinct()
                    .take(3)
                val tasteQueries = recentArtists.map { "$it similar artists music" }
                val tasteSearches = coroutineScope {
                    tasteQueries.map { query ->
                        async {
                            semaphore.withPermit {
                                runCatching { repository.search(query, YoutubeSearchScope.MUSIC_SONGS) }
                                    .getOrDefault(emptyList())
                            }
                        }
                    }.awaitAll().flatten()
                }
                val tastePool = (
                    libraryStore.savedTracks().take(60) +
                    tasteSearches
                ).distinctBy { it.url }

                val batch = RecommendationMixer.mix(
                    tasteTracks = tastePool,
                    genreTracks = genreResults,
                    seed = seed,
                    excludedIds = recommendationHistory.shownIds(),
                    limit = 20
                )
                if (generation != recommendationsGeneration || isFinishing || isDestroyed) return@launch

                if (batch.isEmpty()) {
                    status.text = "Recommendations are unavailable right now."
                    return@launch
                }
                recommendationHistory.remember(batch)
                renderRecommendationTiles(batch)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == recommendationsGeneration && !isFinishing && !isDestroyed) {
                    status.text = "Recommendations unavailable · Retry"
                    status.setOnClickListener { loadHomeShelves() }
                }
            }
        }
    }

    private fun readGenreBuckets(): List<GenreBucket> = runCatching {
        val raw = assets.open("genre-buckets.json").bufferedReader().use { it.readText() }
        val array = JSONArray(org.json.JSONObject(raw).getJSONArray("buckets").toString())
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val label = item.optString("label").trim()
                val query = item.optString("query").trim()
                if (id.isNotEmpty() && label.isNotEmpty() && query.isNotEmpty()) {
                    add(GenreBucket(id, label, query))
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun renderRecommendationTiles(tracks: List<CatalogTrack>) {
        homeShelvesContainer.removeAllViews()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text("Recommendations", 19f, primaryText, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(NexusUi.actionButton(this, "Reroll", primary = false).apply {
            setOnClickListener { loadHomeShelves() }
        }, LinearLayout.LayoutParams(-2, dp(42)))
        homeShelvesContainer.addView(header, verticalParams(12))

        val horizontal = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tracks.forEach { track -> row.addView(buildRecommendationTile(track)) }
        horizontal.addView(row)
        homeShelvesContainer.addView(horizontal, verticalParams(10))
    }

    private fun buildRecommendationTile(track: CatalogTrack): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(142), -2).apply { marginEnd = dp(14) }
        }
        val artFrame = FrameLayout(this)
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(surfaceRaised, NexusDesignSystem.Shape.media)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        track.thumbnailUrl?.let { art.load(it) }
        artFrame.addView(art, FrameLayout.LayoutParams(-1, -1))
        artFrame.setOnClickListener { startResolvingTrack(track) }
        artFrame.contentDescription = "Play " + track.title
        tile.addView(artFrame, LayoutParams(-1, dp(142)))

        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        detail.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setOnClickListener { startResolvingTrack(track) }
                addView(text(track.title.ifBlank { "Untitled" }, 13f, primaryText, bold = true, maxLines = 2))
                addView(text(track.artist ?: "Unknown artist", 11f, secondaryText, maxLines = 1).apply {
                    setPadding(0, dp(3), 0, 0)
                })
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        detail.addView(TextView(this).apply {
            text = "⋮"
            textSize = 20f
            setTextColor(secondaryText)
            gravity = Gravity.CENTER
            minWidth = dp(36)
            minHeight = dp(44)
            contentDescription = "More actions for " + track.title
            setOnClickListener { showTrackMenu(this, track) }
        })
        tile.addView(detail, LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return tile
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
            background = rounded(surface, NexusDesignSystem.Shape.large)
            setPadding(dp(10), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { startResolvingTrack(track) }
            contentDescription = "Play ${track.title.ifBlank { "Untitled" }} by ${track.artist ?: "Unknown artist"}"
        }
        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(surfaceRaised, NexusDesignSystem.Shape.media)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
        card.addView(TextView(this).apply {
            text = "⋮"
            textSize = 22f
            setTextColor(secondaryText)
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            contentDescription = "More actions for " + track.title
            setOnClickListener { showTrackMenu(this, track) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        return card
    }

    private fun showTrackMenu(anchor: View, track: CatalogTrack) {
        PopupMenu(this, anchor).apply {
            menu.add(if (libraryStore.isSaved(track)) "Remove from Saved" else "Save")
            menu.add(if (libraryStore.isWatchLater(track)) "Remove from Watch later" else "Watch later")
            menu.add("Add to playlist")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Save" -> {
                        libraryStore.save(track)
                        NexusHaptics.confirm(anchor, experienceSettings)
                        renderSearchResults()
                    }
                    "Remove from Saved" -> {
                        libraryStore.remove(track)
                        renderSearchResults()
                    }
                    "Watch later" -> {
                        libraryStore.addToWatchLater(track)
                        NexusHaptics.confirm(anchor, experienceSettings)
                    }
                    "Remove from Watch later" -> libraryStore.removeFromWatchLater(track)
                    "Add to playlist" -> showPlaylistChooser(track)
                }
                true
            }
            show()
        }
    }

    private fun showPlaylistChooser(track: CatalogTrack) {
        val playlists = listOf("Watch later") + libraryStore.playlistNames() + "New playlist…"
        AlertDialog.Builder(this)
            .setTitle("Add to playlist")
            .setItems(playlists.toTypedArray()) { _, index ->
                when (index) {
                    0 -> libraryStore.addToWatchLater(track)
                    playlists.lastIndex -> promptCreatePlaylist(track)
                    else -> libraryStore.addToPlaylist(track, playlists[index])
                }
                if (index < playlists.lastIndex) {
                    NexusHaptics.confirm(mainScroll, experienceSettings)
                    Toast.makeText(this, "Added to " + playlists[index], Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun promptCreatePlaylist(track: CatalogTrack?) {
        val input = android.widget.EditText(this).apply {
            hint = "Playlist name"
            isSingleLine = true
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("New playlist")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (libraryStore.createPlaylist(name)) {
                    track?.let { libraryStore.addToPlaylist(it, name) }
                    Toast.makeText(this, "Playlist created", Toast.LENGTH_SHORT).show()
                    if (activeContentKey == "playlists") showPlaylists()
                } else {
                    Toast.makeText(this, "Enter a new playlist name.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
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
            intent.getBooleanExtra(EXTRA_OPEN_SEARCH, false) -> showSearch()
            intent.getBooleanExtra(EXTRA_OPEN_LIBRARY, false) -> showLibrary("Saved", libraryStore.savedTracks(), allowRemove = true)
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
        const val EXTRA_OPEN_SEARCH = "com.nexus.music.extra.OPEN_SEARCH"
        const val EXTRA_OPEN_YOU = "com.nexus.music.extra.OPEN_YOU"
    }
}

/** The mini-player exists only while a real media item is actively loaded. Paused items remain visible. */
internal fun shouldShowMiniPlayer(snapshot: PlaybackSnapshot): Boolean =
    snapshot.sourceId.isNotBlank() &&
        snapshot.playbackState != Player.STATE_IDLE &&
        snapshot.playbackState != Player.STATE_ENDED
