package com.nexus.music.rooms

import android.app.Activity
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.Player
import coil.load
import com.nexus.music.MainActivity
import com.nexus.music.catalog.CatalogTrack
import com.nexus.music.catalog.CatalogFormatting
import com.nexus.music.catalog.ResolvedAudio
import com.nexus.music.catalog.YoutubeCatalogRepository
import com.nexus.music.catalog.YoutubeSearchScope
import com.nexus.music.ui.NexusUi
import com.nexus.music.ui.SearchPanel
import com.nexus.music.playback.PlaybackConnection
import com.nexus.music.playback.PlaybackService
import com.nexus.music.rooms.domain.RoomConnectionState
import com.nexus.music.rooms.domain.RoomController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app prototype for private two-person listening rooms.
 *
 * Chat presentation stays in this Activity. Playback synchronization is owned by PlaybackService,
 * which observes the MediaSession's one ExoPlayer and reconciles the authoritative room snapshot.
 * This Activity only observes room state and refreshes the room's chat history.
 */
class ListeningRoomActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var status: TextView
    private lateinit var codeInput: EditText
    private lateinit var joinButton: Button
    private lateinit var createButton: Button
    private lateinit var roomPanel: LinearLayout
    private lateinit var searchScroll: ScrollView
    private lateinit var searchPanel: SearchPanel
    private lateinit var miniPlayerProgress: SeekBar
    private lateinit var miniPlayerArtwork: ImageView
    private lateinit var miniPlayerTitle: TextView
    private lateinit var miniPlayerSubtitle: TextView
    private lateinit var miniPlayerButton: android.widget.ImageButton
    private lateinit var roomActions: LinearLayout
    private lateinit var inviteText: TextView
    private lateinit var messagesBox: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var copyCodeButton: Button

    private var playback: PlaybackConnection? = null
    private val catalogRepository by lazy { YoutubeCatalogRepository() }
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var selectedScope = YoutubeSearchScope.MUSIC_SONGS
    private var isResolvingTrack = false
    private var isSeeking = false
    private var lastPlaybackSnapshot = PlaybackSnapshot(connected = false)
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            if (!isActivityStarted || isFinishing || isDestroyed) return
            playback?.let { updateRoomPlaybackUi(it.snapshot()) }
        }
    }
    private var roomId: String? = null
    // Distinguishes a new join session even when the same room ID is re-entered.
    private var roomSessionGeneration = 0L
    private var messageRefreshGeneration = 0L
    private var userId: String? = null
    private var inviteCode: String? = null
    // This Activity observes service-owned synchronization; it only polls chat as a UI fallback.
    private var syncJob: Job? = null
    private var roomController: RoomController? = null
    private var roomStateJob: Job? = null
    private var lastChatSignalVersion = 0L
    private var isActivityStarted = false
    private var backInvokedCallback: android.window.OnBackInvokedCallback? = null

    private val background = Color.rgb(12, 14, 18)
    private val surface = Color.rgb(24, 28, 35)
    private val raised = Color.rgb(31, 36, 44)
    private val accent = Color.rgb(167, 240, 106)
    private val foreground = Color.rgb(244, 247, 250)
    private val muted = Color.rgb(160, 172, 187)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@ListeningRoomActivity.background)
            setPadding(dp(20), dp(24), dp(20), dp(18))
        }
        status = label("Connect to create a room or enter an invite code.", 12f, muted)
        status.setPadding(dp(12), dp(12), dp(12), dp(12))
        status.background = rounded(surface, 12)
        status.maxLines = 1
        status.ellipsize = android.text.TextUtils.TruncateAt.END
        status.contentDescription = "Room connection status. Long-press for diagnostics."
        status.setOnLongClickListener {
            if (roomController == null) false else {
                showDiagnostics()
                true
            }
        }
        root.addView(status, params(16))

        roomActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 14)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        createButton = actionButton("Create room", true).apply {
            setOnClickListener { createRoom() }
        }
        roomActions.addView(createButton, params(0))
        codeInput = EditText(this).apply {
            hint = "5-character room code"
            setHintTextColor(muted)
            setTextColor(this@ListeningRoomActivity.foreground)
            textSize = 14f
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(RoomInviteCode.MAX_LENGTH))
            imeOptions = EditorInfo.IME_ACTION_GO
            setBackground(rounded(raised, 10))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { joinRoom(); true } else false
            }
        }
        roomActions.addView(codeInput, params(10))
        joinButton = actionButton("Join", false).apply {
            setOnClickListener { joinRoom() }
        }
        roomActions.addView(joinButton, params(10))
        root.addView(roomActions, params(16))

        roomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val roomHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inviteText = label("Room active", 12f, accent, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        roomHeader.addView(inviteText, LinearLayout.LayoutParams(0, -2, 1f))
        copyCodeButton = actionButton("Copy", false).apply {
            isEnabled = false
            setOnClickListener { inviteCode?.let(::copyCode) }
        }
        roomHeader.addView(copyCodeButton)
        roomHeader.addView(actionButton("Leave", false).apply {
            setOnClickListener { leaveRoom() }
        })
        roomPanel.addView(roomHeader, params(0))

        searchPanel = SearchPanel(
            context = this,
            initialScope = selectedScope,
            onSearchRequested = ::performRoomSearch,
            onScopeSelected = ::selectRoomSearchScope
        )
        searchScroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            addView(searchPanel)
        }
        roomPanel.addView(searchScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(10)
        })

        val miniPlayer = NexusUi.miniPlayer(
            context = this,
            onTogglePlayback = { playback?.togglePlayback() },
            onSeekStarted = { isSeeking = true },
            onSeekChanged = { isSeeking = true },
            onSeekFinished = { progress ->
                val duration = lastPlaybackSnapshot.durationMs
                if (duration > 0L) {
                    playback?.seekTo(duration * progress.toLong() / miniPlayerProgress.max)
                }
                isSeeking = false
            }
        )
        miniPlayerProgress = miniPlayer.progress
        miniPlayerArtwork = miniPlayer.artwork
        miniPlayerTitle = miniPlayer.title
        miniPlayerSubtitle = miniPlayer.subtitle
        miniPlayerButton = miniPlayer.playPause
        roomPanel.addView(miniPlayer.container, params(8))

        roomPanel.addView(label("Chat", 15f, foreground, true), params(10))
        val chatScroll = ScrollView(this).apply {
            background = rounded(surface, 12)
            isFillViewport = false
            clipToPadding = false
        }
        messagesBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        chatScroll.addView(messagesBox)
        roomPanel.addView(chatScroll, LinearLayout.LayoutParams(-1, dp(132)).apply {
            topMargin = dp(7)
            bottomMargin = dp(8)
        })

        val sendRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        messageInput = EditText(this).apply {
            hint = "Write a message…"
            setHintTextColor(muted)
            setTextColor(this@ListeningRoomActivity.foreground)
            textSize = 14f
            maxLines = 3
            setBackground(rounded(surface, 10))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        sendRow.addView(messageInput, LinearLayout.LayoutParams(0, -2, 1f))
        sendButton = actionButton("Send", true).apply {
            setOnClickListener { sendMessage() }
        }
        sendRow.addView(sendButton, LinearLayout.LayoutParams(-2, dp(44)).apply {
            marginStart = dp(8)
        })
        roomPanel.addView(sendRow, params(0))
        root.addView(roomPanel, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(12)
        })

        setContentView(root)
        registerBackNavigation()
        playback = PlaybackConnection(
            context = this,
            onReady = { restoreActiveRoomIfPresent() },
            onStateChanged = ::updateRoomPlaybackUi,
            onError = { message ->
                miniPlayerSubtitle.text = message
                status.text = "Player: $message"
            }
        )
    }

    private var restoreRoomAttempted = false

    /**
     * PlaybackService can outlive this Activity. If Android recreates the Activity while
     * the service still owns an active room, restore the room UI without resetting the
     * controller session or its realtime/heartbeat/drift jobs.
     */
    private fun restoreActiveRoomIfPresent() {
        if (restoreRoomAttempted || roomId != null) return
        // Prefer the live service session, but fall back to the stored room UUID when
        // Android stopped the paused service or process while the Activity was absent.
        val activeRoomId = PlaybackService.currentRoomController()?.state?.value?.roomId
            ?: ActiveRoomSessionStore.read(this)
            ?: return
        restoreRoomAttempted = true
        scope.launch {
            try {
                val api = PlaybackService.awaitRoomsApi()
                if (roomId != null || isFinishing || isDestroyed) return@launch
                userId = api.identity().userId
                enterRoom(activeRoomId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                restoreRoomAttempted = false
                status.text = "Couldn't restore the active room: ${error.message ?: "reconnect and try again."}"
            }
        }
    }

    private fun createRoom() {
        if (syncJob?.isActive == true) return
        setBusy(true, "Creating a private room…")
        scope.launch {
            try {
                val api = PlaybackService.awaitRoomsApi()
                val identity = api.identity()
                userId = identity.userId
                val created = api.createRoom()
                inviteCode = created.inviteCode
                enterRoom(created.roomId)
                status.text = "Room created. Share the invite code with the other person."
                inviteText.text = "Room code: ${created.inviteCode}"
                android.app.AlertDialog.Builder(this@ListeningRoomActivity)
                    .setTitle("Room created")
                    .setMessage("Share this invite code:\n\n${created.inviteCode}\n\nThe other person can enter it in Nexus.")
                    copyCodeButton.isEnabled = true
                Toast.makeText(this@ListeningRoomActivity, "Room created · copy the code to invite someone.", Toast.LENGTH_LONG).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status.text = error.message ?: "Couldn't create the room."
            } finally {
                setBusy(false)
            }
        }
    }

    private fun joinRoom() {
        if (syncJob?.isActive == true) return
        val code = codeInput.text.toString().trim()
        val normalizedCode = RoomInviteCode.normalize(code)
        if (!RoomInviteCode.isValid(normalizedCode)) {
            codeInput.error = "Enter the 5-character room code"
            return
        }
        codeInput.error = null
        setBusy(true, "Joining room…")
        scope.launch {
            try {
                val api = PlaybackService.awaitRoomsApi()
                val identity = api.identity()
                userId = identity.userId
                val joinedId = api.joinRoom(normalizedCode)
                inviteCode = normalizedCode
                enterRoom(joinedId)
                status.text = "Connected. This room has its own chat history."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status.text = error.message ?: "Couldn't join this room."
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun enterRoom(id: String) {
        roomSessionGeneration += 1L
        val sessionGeneration = roomSessionGeneration
        roomId = id
        roomPanel.visibility = View.VISIBLE
        roomActions.visibility = View.GONE
        createButton.visibility = View.GONE
        codeInput.visibility = View.GONE
        joinButton.visibility = View.GONE
        inviteText.text = inviteCode?.let { "Room code: $it" } ?: "Room connected"
        copyCodeButton.isEnabled = inviteCode != null
        status.text = "Connected · starting room engine…"
        updateRoomPlaybackUi(playback?.snapshot() ?: PlaybackSnapshot(connected = false))

        try {
            // PlaybackConnection has already caused PlaybackService to start. The room
            // controller is obtained from that service and survives Activity navigation.
            val controller = PlaybackService.awaitRoomController()
            if (roomId != id || roomSessionGeneration != sessionGeneration) return
            roomController = controller
            // The controller may already own this room after Activity recreation.
            // Observe the live session instead of tearing down and restarting its jobs.
            if (controller.state.value.roomId != id) {
                controller.attach(id)
            }
            if (roomId != id || roomSessionGeneration != sessionGeneration) return
            ActiveRoomSessionStore.save(this@ListeningRoomActivity, id)

            lastChatSignalVersion = controller.state.value.chatSignalVersion
            roomStateJob?.cancel()
            roomStateJob = scope.launch {
                controller.state.collect { roomState ->
                    if (roomId != id || roomSessionGeneration != sessionGeneration) {
                        return@collect
                    }
                    if (roomState.roomId == null) {
                        exitRoom()
                        return@collect
                    }
                    status.text = roomState.lastError ?: roomState.statusMessage ?: when (roomState.connection) {
                        RoomConnectionState.CONNECTED ->
                            if (roomState.realtimeConnected) "Connected · live sync"
                            else "Connected · restoring live sync"
                        RoomConnectionState.CONNECTING -> "Connected · restoring room connection…"
                        RoomConnectionState.RECONCILING -> "Connected · reconciling playback…"
                        RoomConnectionState.ERROR -> "Room connection needs attention."
                        RoomConnectionState.DISCONNECTED -> "Room disconnected."
                    }
                    if (roomState.chatSignalVersion > lastChatSignalVersion) {
                        lastChatSignalVersion = roomState.chatSignalVersion
                        if (!isActivityStarted) return@collect
                        try {
                            refreshMessages()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            status.text = "Chat reconnecting · ${error.message ?: "retrying"}"
                        }
                    }
                }
            }

            // Chat polling is a visible-UI fallback only; the service owns playback sync.
            startChatFallbackSync()
            refreshMessages()
        } catch (cancelled: CancellationException) {
            if (roomId == id && roomSessionGeneration == sessionGeneration) {
                exitRoom(clearPersistedRoom = false)
            }
            throw cancelled
        } catch (error: Exception) {
            if (roomId == id && roomSessionGeneration == sessionGeneration) {
                exitRoom(clearPersistedRoom = false)
            }
            throw error
        }
    }

    private fun schedulePlaybackProgress(snapshot: PlaybackSnapshot) {
        progressHandler.removeCallbacks(progressTicker)
        if (isActivityStarted && snapshot.isPlaying && !isFinishing && !isDestroyed) {
            progressHandler.postDelayed(progressTicker, 500L)
        }
    }

    private fun updateRoomPlaybackUi(snapshot: PlaybackSnapshot) {
        lastPlaybackSnapshot = snapshot
        if (!::miniPlayerSubtitle.isInitialized) return
        schedulePlaybackProgress(snapshot)
        if (!snapshot.connected) {
            miniPlayerSubtitle.text = "Connecting to playback service…"
            miniPlayerButton.isEnabled = false
            return
        }
        if (snapshot.title.isNotBlank()) {
            miniPlayerTitle.text = snapshot.title
            miniPlayerSubtitle.text = when (snapshot.playbackState) {
                Player.STATE_BUFFERING -> "Loading audio…"
                Player.STATE_READY -> if (snapshot.playWhenReady) {
                    "Playing" + snapshot.artist.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
                } else {
                    "Paused" + snapshot.artist.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
                }
                Player.STATE_ENDED -> "Finished"
                else -> snapshot.artist.ifBlank { "Ready" }
            }
            miniPlayerButton.isEnabled = true
        } else {
            miniPlayerTitle.text = "Nothing playing"
            miniPlayerSubtitle.text = "Choose a search result"
            miniPlayerButton.isEnabled = false
        }
        NexusUi.setPlaying(this, miniPlayerButton, snapshot.playWhenReady)
        if (!isSeeking) {
            miniPlayerProgress.isEnabled = snapshot.durationMs > 0L
            miniPlayerProgress.progress = if (snapshot.durationMs > 0L) {
                ((snapshot.positionMs.toDouble() / snapshot.durationMs.toDouble()) * miniPlayerProgress.max)
                    .toInt().coerceIn(0, miniPlayerProgress.max)
            } else 0
        }
    }

    private fun selectRoomSearchScope(scope: YoutubeSearchScope) {
        if (selectedScope == scope) return
        selectedScope = scope
        searchPanel.setSelectedScope(scope)
        if (searchPanel.queryInput.text.toString().isNotBlank()) performRoomSearch()
    }

    private fun performRoomSearch() {
        val query = searchPanel.queryInput.text.toString().trim()
        if (query.isEmpty()) {
            searchGeneration++
            searchJob?.cancel()
            searchJob = null
            searchPanel.statusText.text = ""
            searchPanel.statusText.visibility = View.GONE
            searchPanel.resultsContainer.removeAllViews()
            return
        }
        searchJob?.cancel()
        val generation = ++searchGeneration
        val requestScope = selectedScope
        searchPanel.statusText.text = "Searching…"
        searchPanel.statusText.visibility = View.VISIBLE
        searchPanel.resultsContainer.removeAllViews()
        searchJob = scope.launch {
            try {
                val results = catalogRepository.search(query, requestScope)
                if (generation != searchGeneration || roomId == null) return@launch
                searchPanel.resultsContainer.removeAllViews()
                if (results.isEmpty()) {
                    searchPanel.statusText.text = "No results"
                    searchPanel.statusText.visibility = View.VISIBLE
                } else {
                    searchPanel.statusText.text = ""
                    searchPanel.statusText.visibility = View.GONE
                    results.forEachIndexed { index, track ->
                        searchPanel.resultsContainer.addView(
                            roomResultCard(track),
                            LinearLayout.LayoutParams(-1, -2).apply {
                                topMargin = dp(if (index == 0) 0 else 10)
                            }
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == searchGeneration) {
                    searchPanel.statusText.text = "Search failed · Retry"
                    searchPanel.statusText.visibility = View.VISIBLE
                    searchPanel.statusText.setOnClickListener { performRoomSearch() }
                }
            } finally {
                if (generation == searchGeneration) searchJob = null
            }
        }
    }

    private fun roomResultCard(track: CatalogTrack): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 14)
            setPadding(dp(10), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { resolveRoomTrack(track) }
        }
        val artwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(raised, 10)
            contentDescription = "Track artwork"
        }
        track.thumbnailUrl?.let(artwork::load)
        card.addView(artwork, LinearLayout.LayoutParams(dp(88), dp(64)))
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        details.addView(label(track.title.ifBlank { "Untitled" }, 14f, foreground, true))
        details.addView(label(track.artist ?: "Unknown artist", 12f, muted))
        val metadata = listOfNotNull(
            track.durationSeconds?.takeIf { it >= 0 }?.let(CatalogFormatting::duration),
            track.viewCount?.takeIf { it >= 0 }?.let(CatalogFormatting::views)
        ).joinToString("  ·  ")
        if (metadata.isNotBlank()) details.addView(label(metadata, 10f, muted))
        card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        return card
    }

    private fun resolveRoomTrack(track: CatalogTrack) {
        if (isResolvingTrack) return
        isResolvingTrack = true
        miniPlayerSubtitle.text = "Preparing audio…"
        scope.launch {
            try {
                val audio = catalogRepository.resolveAudio(track.url)
                if (roomId == null || isFinishing || isDestroyed) return@launch
                miniPlayerTitle.text = audio.title
                miniPlayerSubtitle.text = "Sending to room playback…"
                miniPlayerArtwork.load(audio.thumbnailUrl?.takeIf(String::isNotBlank))
                miniPlayerButton.isEnabled = true
                // This is the same MediaController command path used by Home. The
                // MediaSession's existing RoomIntentForwardingPlayer records the
                // selection/play intent for the active RoomController; no sync logic
                // is implemented or invoked from this Activity.
                if (playback?.play(audio) != true) {
                    miniPlayerSubtitle.text = "Player unavailable"
                    status.text = "The room player could not accept this track."
                    return@launch
                }
                miniPlayerSubtitle.text = "Starting shared playback…"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                miniPlayerSubtitle.text = "Couldn't prepare audio"
                status.text = error.message ?: "Choose another result and try again."
            } finally {
                isResolvingTrack = false
            }
        }
    }

    private fun showDiagnostics() {
        val diagnostics = roomController?.diagnostics?.value ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle("Room diagnostics")
            .setMessage(diagnostics.summary)
            .setPositiveButton("Copy log") { _, _ -> copyDiagnostics() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun copyDiagnostics() {
        val diagnostics = roomController?.diagnostics?.value ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Nexus room diagnostics", diagnostics.exportText())
        )
        Toast.makeText(this, "Synchronization log copied", Toast.LENGTH_SHORT).show()
    }

    private fun startChatFallbackSync() {
        val id = roomId ?: return
        val controller = roomController ?: return
        val sessionGeneration = roomSessionGeneration
        if (!isActivityStarted || syncJob?.isActive == true) return

        syncJob = scope.launch {
            while (isActive &&
                roomId == id &&
                roomSessionGeneration == sessionGeneration
            ) {
                val realtimeHealthy = controller.state.value.realtimeConnected
                delay(if (realtimeHealthy) 30_000L else 10_000L)
                if (roomId != id || roomSessionGeneration != sessionGeneration) break
                try {
                    refreshMessages()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    status.text = "Chat reconnecting · ${error.message ?: "connection interrupted"}"
                }
            }
        }
    }

    private fun stopChatFallbackSync() {
        syncJob?.cancel()
        syncJob = null
    }

    private suspend fun refreshMessages() {
        val id = roomId ?: return
        val sessionGeneration = roomSessionGeneration
        val requestGeneration = ++messageRefreshGeneration
        val api = PlaybackService.awaitRoomsApi()
        val messages = api.loadMessages(id)
        // Ignore both cross-room responses and older concurrent reads from this same room.
        if (roomId != id ||
            roomSessionGeneration != sessionGeneration ||
            requestGeneration != messageRefreshGeneration
        ) return
        if (messages.isEmpty()) {
            if (messagesBox.childCount == 1 && messagesBox.getChildAt(0).tag == "empty") return
            messagesBox.removeAllViews()
            messagesBox.addView(
                label("No messages yet. Start the conversation.", 12f, muted).apply {
                    tag = "empty"
                }
            )
            return
        }
        val currentIds = (0 until messagesBox.childCount).mapNotNull { index ->
            messagesBox.getChildAt(index).tag as? String
        }.toSet()
        if (messages.all { it.id in currentIds } && messages.size == currentIds.size) return
        messagesBox.removeAllViews()
        messages.forEach { message ->
                val mine = message.senderId == userId
                val bubble = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(if (mine) Color.rgb(45, 62, 39) else raised, 12)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }
                bubble.addView(label(if (mine) "You" else "Room member", 10f, if (mine) accent else muted, true))
                bubble.addView(label(message.body, 13f, foreground))
                bubble.addView(label(formatTime(message.createdAt), 9f, muted))
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = if (mine) Gravity.END else Gravity.START
                }
                row.tag = message.id
                row.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply {
                    weight = 0f
                    width = (resources.displayMetrics.widthPixels * 0.72f).toInt()
                    bottomMargin = dp(8)
                })
                messagesBox.addView(row)
        }
    }

    private fun sendMessage() {
        val id = roomId ?: return
        val sender = userId ?: return
        val body = messageInput.text.toString().trim()
        if (body.isEmpty()) return
        val sessionGeneration = roomSessionGeneration
        sendButton.isEnabled = false
        scope.launch {
            try {
                val api = PlaybackService.awaitRoomsApi()
                api.sendMessage(id, sender, body)
                if (roomId != id || roomSessionGeneration != sessionGeneration) return@launch
                messageInput.text.clear()
                refreshMessages()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (roomId == id && roomSessionGeneration == sessionGeneration) {
                    status.text = error.message ?: "Message could not be sent."
                }
            } finally {
                if (roomId == id && roomSessionGeneration == sessionGeneration) sendButton.isEnabled = true
            }
        }
    }

    private fun leaveRoom() {
        val id = roomId ?: return
        val sessionGeneration = roomSessionGeneration
        scope.launch {
            var leaveFailed = false
            try {
                val api = PlaybackService.awaitRoomsApi()
                api.leaveRoom(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                leaveFailed = true
            }
            if (roomId != id || roomSessionGeneration != sessionGeneration) return@launch
            exitRoom()
            if (leaveFailed) {
                status.text = "Exited on this phone. The server will release this seat automatically if the connection was interrupted."
            }
        }
    }

    private fun exitRoom(clearPersistedRoom: Boolean = true) {
        // Detaching cancels only this room session. It never releases the service/player.
        roomSessionGeneration += 1L
        messageRefreshGeneration += 1L
        stopChatFallbackSync()
        roomStateJob?.cancel()
        roomStateJob = null
        val controller = roomController
        roomController = null
        if (controller != null) {
            scope.launch { runCatching { controller.detach() } }
        }

        messageInput.text.clear()
        codeInput.text.clear()
        roomId = null
        inviteCode = null
        userId = null
        if (clearPersistedRoom) ActiveRoomSessionStore.clear(this)
        lastChatSignalVersion = 0L
        roomPanel.visibility = View.GONE
        roomActions.visibility = View.VISIBLE
        createButton.visibility = View.VISIBLE
        codeInput.visibility = View.VISIBLE
        joinButton.visibility = View.VISIBLE
        messagesBox.removeAllViews()
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
        searchPanel.queryInput.text.clear()
        searchPanel.statusText.text = ""
        searchPanel.statusText.visibility = View.GONE
        searchPanel.resultsContainer.removeAllViews()
        sendButton.isEnabled = true
        status.text = "Exited this room. Its history remains until one hour of inactivity."
    }

    private fun registerBackNavigation() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val callback = android.window.OnBackInvokedCallback { navigateHome() }
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
            selected = NexusUi.NavigationTab.ROOM,
            onHome = ::navigateHome,
            onRoom = {}
        )

    private fun navigateHome() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    private fun setBusy(busy: Boolean, message: String? = null) {
        createButton.isEnabled = !busy
        joinButton.isEnabled = !busy
        if (message != null) status.text = message
    }

    private fun copyCode(code: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Nexus room code", code))
        Toast.makeText(this, "Invite code copied", Toast.LENGTH_SHORT).show()
    }

    private fun parseTimestamp(value: String): Long? = RoomTimestampParser.parse(value)

    private fun formatTime(raw: String): String = runCatching {
        val date = parseTimestamp(raw) ?: return@runCatching ""
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(date))
    }.getOrDefault("")

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun actionButton(value: String, primary: Boolean) = Button(this).apply {
        text = value
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(if (primary) Color.rgb(14, 20, 12) else this@ListeningRoomActivity.foreground)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(if (primary) accent else raised, 10)
    }

    private fun params(top: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Use the platform back callback on Android 13 and newer.")
    override fun onBackPressed() {
        // Back navigates to Home; it must not destroy the active room session.
        navigateHome()
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true
        startChatFallbackSync()
        if (roomId != null) {
            updateRoomPlaybackUi(playback?.snapshot() ?: PlaybackSnapshot(connected = false))
            scope.launch {
                try {
                    refreshMessages()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    status.text = "Chat reconnecting · ${error.message ?: "retrying"}"
                }
            }
        }
    }

    override fun onStop() {
        isActivityStarted = false
        progressHandler.removeCallbacks(progressTicker)
        stopChatFallbackSync()
        super.onStop()
    }

    override fun onDestroy() {
        isActivityStarted = false
        progressHandler.removeCallbacks(progressTicker)
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
            backInvokedCallback = null
        }
        // Do not detach the service-owned room controller here. The room must survive
        // Activity navigation/recreation while PlaybackService remains alive.
        stopChatFallbackSync()
        roomStateJob?.cancel()
        playback?.close()
        playback = null
        scope.cancel()
        super.onDestroy()
    }
}
