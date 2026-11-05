package com.nexus.music

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.Player
import coil3.load
import com.nexus.music.catalog.CatalogTrack
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

    private lateinit var resultsContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var queryInput: EditText
    private lateinit var searchButton: Button
    private lateinit var updateButton: Button
    private lateinit var updateStatusText: TextView
    private lateinit var updateProgressContainer: LinearLayout
    private lateinit var updateProgressText: TextView
    private lateinit var updateProgressBar: ProgressBar
    private lateinit var scopeButtons: Map<YoutubeSearchScope, Button>
    private lateinit var playerArtwork: ImageView
    private lateinit var playerTitle: TextView
    private lateinit var playerSubtitle: TextView
    private lateinit var playPauseButton: Button
    private lateinit var playbackProgress: SeekBar

    private var playbackConnection: PlaybackConnection? = null
    private var updateCheckJob: Job? = null
    private var downloadJob: Job? = null
    private var pendingUpdate: NexusUpdate? = null
    private var requiresCleanInstall = false
    private var pendingNotificationTrack: CatalogTrack? = null
    private var notificationPermissionRequested = false
    private var selectedScope = YoutubeSearchScope.EVERYTHING
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var isResolving = false
    private var isSeeking = false
    private var lastPlaybackSnapshot = PlaybackSnapshot(connected = false)
    private var backInvokedCallback: android.window.OnBackInvokedCallback? = null

    private val screenBackground = Color.rgb(12, 14, 18)
    private val surface = Color.rgb(24, 28, 35)
    private val surfaceRaised = Color.rgb(31, 36, 44)
    private val accent = Color.rgb(167, 240, 106)
    private val primaryText = Color.rgb(244, 247, 250)
    private val secondaryText = Color.rgb(160, 172, 187)

    private val progressTicker = object : Runnable {
        override fun run() {
            playbackConnection?.let { updatePlaybackUi(it.snapshot()) }
            if (!isFinishing) progressHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = screenBackground
        window.navigationBarColor = screenBackground
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(screenBackground)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(32))
        }

        val appHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appHeader.addView(
            text("NEXUS", 12f, accent, bold = true).apply { letterSpacing = 0.22f },
            LinearLayout.LayoutParams(-2, -2)
        )
        content.addView(appHeader)

        val updatePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
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
        content.addView(text("Find your next\nfavourite track.", 31f, primaryText, bold = true).apply {
            setPadding(0, dp(12), 0, dp(8))
        })
        content.addView(text("Search YouTube's catalogue. Pick a result to start listening.", 14f, secondaryText))

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
            isSingleLine = true
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
            scopesRow.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginEnd = dp(8)
            })
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
        root.addView(buildPlayerPanel(), LinearLayout.LayoutParams(-1, -2))
        root.addView(buildBottomNavigation(), LinearLayout.LayoutParams(-1, dp(62)))
        setContentView(root)
        registerBackNavigation()
        applySystemBarInsets(root)
        connectPlaybackService()
        NexusUpdateScheduler.disableBackgroundUpdates(this)
        requestUpdateNotificationPermission()
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

    private fun buildBottomNavigation(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(surface, 18)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        fun tab(title: String, selected: Boolean, onClick: () -> Unit) = Button(this).apply {
            text = title
            textSize = 14f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (selected) Color.rgb(14, 20, 12) else primaryText)
            background = rounded(if (selected) accent else surfaceRaised, 12)
            setOnClickListener { onClick() }
        }
        bar.addView(tab("Home", true) {}, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            marginEnd = dp(7)
        })
        bar.addView(tab("Room", false) { openRoomPage() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        return bar
    }

    private fun openRoomPage() {
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


    private fun requestUpdateNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionRequested
        ) {
            notificationPermissionRequested = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
        }
    }

    /** Checks the latest stable GitHub release every time Nexus is opened. */
    private fun checkForUpdates() {
        if (updateCheckJob?.isActive == true || downloadJob?.isActive == true) return

        pendingUpdate = null
        updateStatusText.text = "Checking for updates…"
        updateStatusText.setTextColor(secondaryText)
        updateButton.visibility = View.GONE
        updateProgressContainer.visibility = View.GONE

        updateCheckJob = activityScope.launch {
            try {
                when (val result = updateChecker.checkForUpdate(this@MainActivity)) {
                    is UpdateCheckResult.IncompatibleInstallation -> {
                        pendingUpdate = null
                        requiresCleanInstall = true
                        updateStatusText.text =
                            "This copy can't update in place · install the official signed build"
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
                "This copy was signed with a different key. Android blocks in-place updates " +
                    "between different signing keys. Back up anything important, uninstall this " +
                    "copy, then install Nexus from the official GitHub release page. " +
                    "Uninstalling can erase this device's saved anonymous room identity."
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
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surfaceRaised, 16)
            setPadding(dp(14), dp(5), dp(14), dp(12))
        }
        playbackProgress = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) isSeeking = true
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isSeeking = true
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val duration = lastPlaybackSnapshot.durationMs
                    if (duration > 0L) {
                        val target = duration * playbackProgress.progress.toLong() / playbackProgress.max
                        playbackConnection?.seekTo(target)
                    }
                    isSeeking = false
                }
            })
        }
        panel.addView(playbackProgress, LinearLayout.LayoutParams(-1, dp(24)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playerArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(surface, 10)
            contentDescription = "Now playing artwork"
        }
        row.addView(playerArtwork, LinearLayout.LayoutParams(dp(48), dp(48)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        playerTitle = text("Nexus player", 14f, primaryText, bold = true, maxLines = 1)
        playerSubtitle = text("Connecting to playback service…", 12f, secondaryText, maxLines = 1)
        labels.addView(playerTitle)
        labels.addView(playerSubtitle.apply { setPadding(0, dp(4), 0, 0) })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))

        playPauseButton = Button(this).apply {
            text = "▶"
            textSize = 18f
            isAllCaps = false
            isEnabled = false
            setTextColor(Color.rgb(14, 20, 12))
            background = rounded(accent, 12)
            setOnClickListener { playbackConnection?.togglePlayback() }
        }
        row.addView(playPauseButton, LinearLayout.LayoutParams(dp(52), dp(46)))
        panel.addView(row)
        return panel
    }

    private fun connectPlaybackService() {
        playbackConnection = PlaybackConnection(
            context = this,
            onReady = {
                updatePlaybackUi(playbackConnection?.snapshot() ?: PlaybackSnapshot(connected = false))
            },
            onStateChanged = ::updatePlaybackUi,
            onError = { message ->
                statusText.text = "Playback error: $message"
                playerSubtitle.text = message
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun updatePlaybackUi(snapshot: PlaybackSnapshot) {
        lastPlaybackSnapshot = snapshot
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

        playPauseButton.text = if (snapshot.playWhenReady) "Ⅱ" else "▶"
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
        if (query.isEmpty()) {
            searchGeneration++
            searchJob?.cancel()
            searchJob = null
            searchButton.isEnabled = true
            searchButton.text = "Search"
            queryInput.error = "Enter a song, artist or video"
            statusText.text = "Enter a song, artist or video to search."
            resultsContainer.removeAllViews()
            return
        }

        // A newer query or scope always supersedes the previous request.
        searchJob?.cancel()
        queryInput.error = null
        val requestGeneration = ++searchGeneration
        val requestScope = selectedScope
        // Keep the button available so a new query can supersede a slow request.
        searchButton.isEnabled = true
        searchButton.text = "Search"
        statusText.text = "Searching YouTube..."
        resultsContainer.removeAllViews()

        searchJob = activityScope.launch {
            try {
                val results = repository.search(query, requestScope)
                if (requestGeneration != searchGeneration) return@launch

                resultsContainer.removeAllViews()
                if (results.isEmpty()) {
                    statusText.text = "No results found. Try another search or scope."
                } else {
                    statusText.text = "${results.size} results · tap one to start listening"
                    results.forEachIndexed { index, track ->
                        resultsContainer.addView(resultCard(track), verticalParams(if (index == 0) 0 else 10))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration == searchGeneration) {
                    statusText.text = "Search couldn't complete: ${error.message ?: "Please try again."}"
                }
            } finally {
                if (requestGeneration == searchGeneration && !isDestroyed) {
                    searchJob = null
                    searchButton.isEnabled = true
                    searchButton.text = "Search"
                }
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
        return card
    }

    private fun resolve(track: CatalogTrack) {
        if (isResolving) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationPermissionRequested &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequested = true
            pendingNotificationTrack = track
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
            return
        }
        startResolvingTrack(track)
    }

    @Deprecated("Use the system notification permission result to continue the user's playback action.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            val track = pendingNotificationTrack
            pendingNotificationTrack = null
            if (track != null && !isFinishing) {
                // Playback is allowed even when notification permission is declined. Android may
                // show media controls in its media area rather than the regular notification drawer.
                startResolvingTrack(track)
            }
        }
    }

    private fun startResolvingTrack(track: CatalogTrack) {
        if (isResolving) return
        isResolving = true
        statusText.text = "Preparing audio for “${track.title}”…"
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
        playerTitle.text = audio.title
        playerSubtitle.text = "Sending audio to player…"
        playerArtwork.load(audio.thumbnailUrl?.takeIf(String::isNotBlank))
        playPauseButton.isEnabled = true
        if (playbackConnection?.play(audio) != true) {
            statusText.text = "The player could not accept this track. Try selecting it again."
            return
        }
        statusText.text = "Starting playback: ${audio.title}"
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
        if (bold) typeface = Typeface.DEFAULT_BOLD
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
        if (::updateStatusText.isInitialized) checkForUpdates()
        progressHandler.removeCallbacks(progressTicker)
        progressHandler.post(progressTicker)
    }

    override fun onStop() {
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
        private const val REQUEST_POST_NOTIFICATIONS = 1001
    }
}
