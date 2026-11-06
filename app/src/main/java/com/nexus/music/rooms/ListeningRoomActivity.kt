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
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.nexus.music.MainActivity
import com.nexus.music.playback.PlaybackConnection
import com.nexus.music.playback.PlaybackService
import com.nexus.music.rooms.domain.RoomConnectionState
import com.nexus.music.rooms.domain.RoomController
import com.nexus.music.playback.RoomPlayerAdapter
import com.nexus.music.playback.PlaybackSnapshot
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
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var codeInput: EditText
    private lateinit var joinButton: Button
    private lateinit var createButton: Button
    private lateinit var roomPanel: LinearLayout
    private lateinit var roomActions: LinearLayout
    private lateinit var inviteText: TextView
    private lateinit var messagesBox: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var nowPlaying: TextView
    private lateinit var playButton: Button
    private lateinit var seekBar: SeekBar
    private lateinit var diagnosticsText: TextView
    private lateinit var copyDiagnosticsButton: Button

    private var playback: RoomPlayerAdapter? = null
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
    private var diagnosticsJob: Job? = null
    private var lastChatSignalVersion = 0L
    private var isSeeking = false
    private var lastSnapshot = PlaybackSnapshot(connected = false)
    private var backInvokedCallback: android.window.OnBackInvokedCallback? = null

    private val background = Color.rgb(12, 14, 18)
    private val surface = Color.rgb(24, 28, 35)
    private val raised = Color.rgb(31, 36, 44)
    private val accent = Color.rgb(167, 240, 106)
    private val foreground = Color.rgb(244, 247, 250)
    private val muted = Color.rgb(160, 172, 187)

    private val playerTicker = object : Runnable {
        override fun run() {
            val snapshot = playback?.snapshot() ?: PlaybackSnapshot(connected = false)
            renderPlayback(snapshot)
            if (!isFinishing && !isDestroyed) handler.postDelayed(this, 500L)
        }
    }

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
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("NEXUS", 12f, accent, true), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)

        root.addView(label("Listening room", 28f, foreground, true), params(16))
        root.addView(label(
            "Private rooms for two. Chat history is kept separately in each room.",
            13f, muted
        ), params(6))

        status = label("Connect to create a room or enter an invite code.", 12f, muted)
        status.setPadding(dp(12), dp(12), dp(12), dp(12))
        status.background = rounded(surface, 12)
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
        joinButton = actionButton("Join room", false).apply {
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
        inviteText = label("Room active", 12f, accent, true)
        roomHeader.addView(inviteText, LinearLayout.LayoutParams(0, -2, 1f))
        roomHeader.addView(actionButton("Exit room", false).apply {
            setOnClickListener { leaveRoom() }
        })
        roomPanel.addView(roomHeader, params(0))

        roomPanel.addView(label("Now playing together", 15f, foreground, true), params(14))
        nowPlaying = label("Nothing playing yet", 12f, muted)
        roomPanel.addView(nowPlaying, params(5))
        seekBar = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) isSeeking = true
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { isSeeking = true }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val duration = lastSnapshot.durationMs
                    if (duration > 0L) {
                        playback?.seekTo(duration * seekBar.progress / seekBar.max)
                    }
                    isSeeking = false
                }
            })
        }
        roomPanel.addView(seekBar, params(0))
        playButton = actionButton("Play / pause", true).apply {
            setOnClickListener { playback?.togglePlayback() }
        }
        roomPanel.addView(playButton, params(4))

        val diagnosticsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        diagnosticsText = label("Sync diagnostics · waiting for measurements", 10f, muted)
        diagnosticsRow.addView(diagnosticsText, LinearLayout.LayoutParams(0, -2, 1f))
        copyDiagnosticsButton = actionButton("Copy log", false).apply {
            setOnClickListener { copyDiagnostics() }
            isEnabled = false
        }
        diagnosticsRow.addView(copyDiagnosticsButton, LinearLayout.LayoutParams(-2, dp(38)))
        roomPanel.addView(diagnosticsRow, params(5))

        roomPanel.addView(label("Room chat", 15f, foreground, true), params(16))
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
        roomPanel.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
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
        root.addView(buildBottomNavigation(), LinearLayout.LayoutParams(-1, dp(62)))

        setContentView(root)
        registerBackNavigation()
        playback = PlaybackConnection(
            context = this,
                        onReady = {
                renderPlayback(playback?.snapshot() ?: PlaybackSnapshot(false))
                restoreActiveRoomIfPresent()
            },
            onStateChanged = ::onPlaybackChanged,
            onError = { message -> status.text = "Player: $message" }
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
                inviteText.text = "Invite code: ${created.inviteCode}"
                android.app.AlertDialog.Builder(this@ListeningRoomActivity)
                    .setTitle("Room created")
                    .setMessage("Share this invite code:\n\n${created.inviteCode}\n\nThe other person can enter it in Nexus.")
                    .setPositiveButton("Copy code") { _, _ -> copyCode(created.inviteCode) }
                    .setNegativeButton("Close", null)
                    .show()
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
        inviteText.text = inviteCode?.let { "Invite code: $it" } ?: "Room connected"
        status.text = "Connected · starting room engine…"

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

            diagnosticsJob?.cancel()
            diagnosticsJob = scope.launch {
                controller.diagnostics.collect { diagnostics ->
                    diagnosticsText.text = diagnostics.summary
                    copyDiagnosticsButton.isEnabled = diagnostics.events.isNotEmpty()
                }
            }
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
                    status.text = roomState.lastError ?: when (roomState.connection) {
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

            // Chat remains a UI/data concern. Realtime chat invalidations arrive from the
            // service-owned subscription; this bounded poll is only the missed-event fallback.
            syncJob?.cancel()
            syncJob = scope.launch {
                while (isActive &&
                    roomId == id &&
                    roomSessionGeneration == sessionGeneration
                ) {
                    try {
                        refreshMessages()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        status.text = "Chat reconnecting · ${error.message ?: "connection interrupted"}"
                    }
                    val realtimeHealthy = controller.state.value.realtimeConnected
                    delay(if (realtimeHealthy) 30_000L else 10_000L)
                }
            }
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

    private fun copyDiagnostics() {
        val diagnostics = roomController?.diagnostics?.value ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Nexus room diagnostics", diagnostics.exportText())
        )
        Toast.makeText(this, "Synchronization log copied", Toast.LENGTH_SHORT).show()
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

    private fun onPlaybackChanged(snapshot: PlaybackSnapshot) {
        lastSnapshot = snapshot
        renderPlayback(snapshot)
    }

    private fun renderPlayback(snapshot: PlaybackSnapshot) {
        lastSnapshot = snapshot
        nowPlaying.text = if (snapshot.title.isBlank()) {
            "Nothing playing yet"
        } else {
            "${snapshot.title}${snapshot.artist.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
        }
        playButton.text = if (snapshot.playWhenReady) "Pause" else "Play"
        seekBar.isEnabled = snapshot.durationMs > 0L
        if (!isSeeking && snapshot.durationMs > 0L) {
            seekBar.progress = ((snapshot.positionMs.toDouble() / snapshot.durationMs) * seekBar.max)
                .toInt().coerceIn(0, seekBar.max)
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
        syncJob?.cancel()
        syncJob = null
        roomStateJob?.cancel()
        roomStateJob = null
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        val controller = roomController
        roomController = null
        if (controller != null) {
            scope.launch { runCatching { controller.detach() } }
        }

        messageInput.text.clear()
        codeInput.text.clear()
        isSeeking = false
        roomId = null
        inviteCode = null
        userId = null
        if (clearPersistedRoom) ActiveRoomSessionStore.clear(this)
        lastChatSignalVersion = 0L
        lastSnapshot = playback?.snapshot() ?: PlaybackSnapshot(connected = false)
        roomPanel.visibility = View.GONE
        roomActions.visibility = View.VISIBLE
        createButton.visibility = View.VISIBLE
        codeInput.visibility = View.VISIBLE
        joinButton.visibility = View.VISIBLE
        messagesBox.removeAllViews()
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
            setTextColor(if (selected) Color.rgb(14, 20, 12) else this@ListeningRoomActivity.foreground)
            background = rounded(if (selected) accent else raised, 12)
            setOnClickListener { onClick() }
        }
        bar.addView(tab("Home", false) { navigateHome() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            marginEnd = dp(7)
        })
        bar.addView(tab("Room", true) {}, LinearLayout.LayoutParams(0, dp(46), 1f))
        return bar
    }

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
        handler.removeCallbacks(playerTicker)
        handler.post(playerTicker)
    }

    override fun onStop() {
        handler.removeCallbacks(playerTicker)
        super.onStop()
    }

    override fun onDestroy() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
            backInvokedCallback = null
        }
        // Do not detach the service-owned room controller here. The room must survive
        // Activity navigation/recreation while PlaybackService remains alive.
        syncJob?.cancel()
        roomStateJob?.cancel()
        diagnosticsJob?.cancel()
        handler.removeCallbacks(playerTicker)
        playback?.close()
        playback = null
        scope.cancel()
        super.onDestroy()
    }
}
