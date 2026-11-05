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
import android.os.SystemClock
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
import androidx.media3.common.Player
import com.nexus.music.catalog.YoutubeCatalogRepository
import com.nexus.music.MainActivity
import com.nexus.music.playback.PlaybackConnection
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app prototype for private two-person listening rooms.
 *
 * Chat and playback are room-scoped. This Activity stays in the task back stack while the user
 * switches to Home, so its room sync loop continues to observe the shared MediaSession player.
 * Supabase Realtime delivers low-latency playback updates; authenticated REST polling remains
 * as a recovery path for missed events and temporary WebSocket outages.
 */
class ListeningRoomActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api by lazy { RoomsApi(this) }
    private val catalog by lazy { YoutubeCatalogRepository() }
    private val mediaResolver by lazy { YoutubeRoomMediaResolver(catalog) }
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

    private var playback: RoomPlayerAdapter? = null
    private var roomId: String? = null
    private var userId: String? = null
    private var inviteCode: String? = null
    private var syncJob: Job? = null
    private var driftJob: Job? = null
    private var lastRemotePlayback: RoomPlayback? = null
    private var realtime: RoomRealtimeClient? = null
    private var realtimeConnected = false
    private val remoteApplyMutex = Mutex()
    private val driftController = DriftCorrectionController()
    private val remoteApplyRetryPolicy = RemoteApplyRetryPolicy()
    private var isSeeking = false
    private var applyingRemoteUntil = 0L
    private var applyingRemote = false
    private var lastPublishedActionSequence = -1L
    private var lastRemoteRevision = 0L
    private var lastPublishedSource = ""
    private var lastPublishedPlaying = false
    private var lastPublishedPositionMs = 0L
    private var lastPublishedAtElapsed = 0L
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
            onReady = { renderPlayback(playback?.snapshot() ?: PlaybackSnapshot(false)) },
            onStateChanged = ::onPlaybackChanged,
            onError = { message -> status.text = "Player: $message" }
        )
    }

    private fun createRoom() {
        if (syncJob?.isActive == true) return
        setBusy(true, "Creating a private room…")
        scope.launch {
            try {
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
        roomId = id
        roomPanel.visibility = View.VISIBLE
        roomActions.visibility = View.GONE
        createButton.visibility = View.GONE
        codeInput.visibility = View.GONE
        joinButton.visibility = View.GONE
        inviteText.text = inviteCode?.let { "Invite code: $it" } ?: "Room connected"
        status.text = "Connected · calibrating room clock…"
        runCatching { api.calibrateServerClock() }
        status.text = "Connected · starting live sync…"

        driftJob?.cancel()
        driftJob = scope.launch {
            while (isActive && roomId == id) {
                lastRemotePlayback?.let(::correctLocalDrift)
                delay(500L)
            }
        }

        realtime?.close()
        realtime = RoomRealtimeClient(
            api = api,
            roomId = id,
            scope = scope,
            onPlaybackSignal = {
                scope.launch {
                    runCatching { refreshRemotePlayback(id) }
                        .onFailure { error ->
                            if (error !is CancellationException) {
                                status.text = "Playback reconnecting · ${error.message ?: "retrying"}"
                            }
                        }
                }
            },
            onChatSignal = {
                scope.launch {
                    if (roomId == id) {
                        runCatching { refreshMessages() }
                            .onFailure { error ->
                                if (error !is CancellationException) {
                                    status.text = "Chat reconnecting · ${error.message ?: "retrying"}"
                                }
                            }
                    }
                }
            },
            onConnectionChanged = { connected ->
                scope.launch {
                    realtimeConnected = connected
                    if (roomId == id) {
                        status.text = if (connected) {
                            "Connected · live sync"
                        } else {
                            "Connected · restoring live sync"
                        }
                    }
                }
            }
        ).also { it.start() }

        syncJob?.cancel()
        syncJob = scope.launch {
            var lastHeartbeat = SystemClock.elapsedRealtime() - 25_000L
            var lastClockCalibration = SystemClock.elapsedRealtime()
            var consecutiveFailures = 0
            while (isActive && roomId == id) {
                var iterationFailed = false
                val now = SystemClock.elapsedRealtime()
                if (now - lastClockCalibration >= 300_000L) {
                    runCatching { api.calibrateServerClock() }
                    lastClockCalibration = SystemClock.elapsedRealtime()
                }
                if (now - lastHeartbeat >= 25_000L) {
                    try {
                        if (!api.touchRoom(id)) {
                            exitRoom()
                            status.text = "This room has expired or your membership ended. Join with a current invite code."
                            return@launch
                        }
                        lastHeartbeat = now
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        iterationFailed = true
                        status.text = "Room reconnecting · ${error.message ?: "heartbeat failed"}"
                    }
                }

                try {
                    refreshMessages()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    iterationFailed = true
                    status.text = "Chat reconnecting · ${error.message ?: "connection interrupted"}"
                }

                try {
                    refreshRemotePlayback(id)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    iterationFailed = true
                    status.text = "Playback reconnecting · ${error.message ?: "connection interrupted"}"
                }

                try {
                    publishLocalPlaybackIfChanged()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    iterationFailed = true
                    status.text = "Playback update failed · ${error.message ?: "try again"}"
                }

                consecutiveFailures = if (iterationFailed) {
                    (consecutiveFailures + 1).coerceAtMost(4)
                } else {
                    0
                }
                // Realtime carries immediate playback changes. REST remains a safety net:
                // faster while disconnected, slower while the socket is healthy.
                val baseDelay = if (realtimeConnected) 8_000L else 2_000L
                val nextDelay = if (consecutiveFailures == 0) {
                    baseDelay
                } else {
                    (baseDelay * (1L shl consecutiveFailures)).coerceAtMost(20_000L)
                }
                delay(nextDelay)
            }
        }
    }

    private suspend fun refreshMessages() {
        val id = roomId ?: return
        val messages = api.loadMessages(id)
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
        sendButton.isEnabled = false
        scope.launch {
            try {
                api.sendMessage(id, sender, body)
                messageInput.text.clear()
                refreshMessages()
            } catch (error: Exception) {
                status.text = error.message ?: "Message could not be sent."
            } finally {
                sendButton.isEnabled = true
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

    private suspend fun publishLocalPlaybackIfChanged() {
        val id = roomId ?: return
        val sender = userId ?: return
        if (applyingRemote || SystemClock.elapsedRealtime() < applyingRemoteUntil) return
        val snapshot = playback?.snapshot() ?: return
        if (!snapshot.connected || snapshot.sourceId.isBlank() || snapshot.title.isBlank()) return

        val trackChanged = snapshot.sourceId != lastPublishedSource
        val userActionChanged = snapshot.userActionSequence > lastPublishedActionSequence
        if (!trackChanged && !userActionChanged) return

        val actionType = if (trackChanged) {
            "TRACK"
        } else {
            when (snapshot.lastUserAction) {
                "PLAY", "PAUSE", "SEEK" -> snapshot.lastUserAction
                else -> return
            }
        }

        val actionId = UUID.randomUUID().toString()
        suspend fun send(expectedRevision: Long): PlaybackPublishResult =
            api.publishPlayback(
                roomId = id,
                expectedRevision = expectedRevision,
                sourceId = snapshot.sourceId,
                sourceUrl = snapshot.sourceId,
                title = snapshot.title,
                artist = snapshot.artist,
                isPlaying = snapshot.playWhenReady,
                positionMs = snapshot.positionMs,
                durationMs = snapshot.durationMs,
                actionId = actionId,
                actionType = actionType,
                originDeviceId = sender
            )

        var result = send(lastRemoteRevision)
        val result = send(lastRemoteRevision)
        if (result.applied) {
            lastRemoteRevision = result.revision
            remoteApplyRetryPolicy.recordSuccess(result.revision)
            rememberPublishedPlayback(snapshot)
        } else {
            // A compare-and-swap conflict means the other phone already published a newer
            // command. Never replay this old action with the new revision: that would let a
            // delayed PLAY or SEEK overwrite a newer PAUSE or track change.
            remoteApplyMutex.withLock {
                val latest = api.loadPlayback(id)
                if (latest != null) {
                    lastRemotePlayback = latest
                    lastRemoteRevision = maxOf(lastRemoteRevision, latest.revision)
                    val localSource = playback?.snapshot()?.sourceId.orEmpty()
                    if (latest.updatedBy != sender || localSource != latest.sourceUrl) {
                        try {
                            applyRemotePlayback(latest)
                            remoteApplyRetryPolicy.recordSuccess(latest.revision)
                            rememberPublishedPlayback(playback?.snapshot())
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            val retryIn = remoteApplyRetryPolicy.recordFailure(
                                latest.revision,
                                SystemClock.elapsedRealtime()
                            )
                            status.text = "Applying the latest room action · retrying in ${retryIn / 1000}s"
                        }
                    } else {
                        rememberPublishedPlayback(snapshot)
                    }
                }
            }
            status.text = "Another device changed playback first. The latest room action takes priority."
        }
    }

    private fun rememberPublishedPlayback(snapshot: PlaybackSnapshot?) {
        if (snapshot == null || snapshot.sourceId.isBlank()) return
        lastPublishedSource = snapshot.sourceId
        lastPublishedPlaying = snapshot.playWhenReady
        lastPublishedPositionMs = snapshot.positionMs
        lastPublishedAtElapsed = SystemClock.elapsedRealtime()
        lastPublishedActionSequence = snapshot.userActionSequence
    }

    private suspend fun refreshRemotePlayback(id: String) {
        remoteApplyMutex.withLock {
            if (roomId != id) return
            val remote = api.loadPlayback(id) ?: return
            lastRemotePlayback = remote
            val isNewRevision = remote.revision > lastRemoteRevision
            if (isNewRevision) lastRemoteRevision = remote.revision

            val localSource = playback?.snapshot()?.sourceId.orEmpty()
            val isOurCurrentState = remote.updatedBy == userId && localSource == remote.sourceUrl
            val shouldApplyNewState = isNewRevision && !isOurCurrentState
            val shouldRetryFailedState = remoteApplyRetryPolicy.shouldRetry(
                remote.revision,
                SystemClock.elapsedRealtime()
            )

            if (isOurCurrentState) {
                remoteApplyRetryPolicy.recordSuccess(remote.revision)
                rememberPublishedPlayback(playback?.snapshot())
            } else if (shouldApplyNewState || shouldRetryFailedState) {
                try {
                    applyRemotePlayback(remote)
                    remoteApplyRetryPolicy.recordSuccess(remote.revision)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val retryIn = remoteApplyRetryPolicy.recordFailure(
                        remote.revision,
                        SystemClock.elapsedRealtime()
                    )
                    status.text = "Shared track isn't ready yet · retrying in ${retryIn / 1000}s"
                }
            }
            correctLocalDrift(remote)
        }
    }

    private fun correctLocalDrift(remote: RoomPlayback) {
        val activePlayer = playback ?: return
        val local = activePlayer.snapshot()
        if (!local.connected || local.sourceId != remote.sourceUrl) return
        if (!remote.isPlaying) {
            activePlayer.resetPlaybackSpeed()
            driftController.reset()
            return
        }
        // Buffering is not drift. Never seek or change speed while Media3 is waiting for data.
        if (local.isBuffering || !local.playWhenReady) return

        val elapsed = parseTimestamp(remote.updatedAt)
            ?.let { (api.serverNowMillis() - it).coerceAtLeast(0L) } ?: 0L
        val expected = RoomPlaybackMath.expectedRemotePositionMs(
            positionMs = remote.positionMs,
            isPlaying = remote.isPlaying,
            elapsedSinceUpdateMs = elapsed,
            durationMs = remote.durationMs
        )
        val drift = local.positionMs - expected
        val correction = driftController.evaluate(
            driftMs = drift,
            isBuffering = local.isBuffering,
            nowMs = SystemClock.elapsedRealtime()
        )
        when (correction.type) {
            DriftCorrectionType.NONE -> Unit
            DriftCorrectionType.SET_SPEED -> activePlayer.setPlaybackSpeed(correction.speed)
            DriftCorrectionType.RESET_SPEED -> activePlayer.resetPlaybackSpeed()
            DriftCorrectionType.SEEK -> {
                activePlayer.resetPlaybackSpeed()
                activePlayer.seekTo(expected, userInitiated = false)
            }
        }
    }

    private suspend fun applyRemotePlayback(remote: RoomPlayback) {
        val activePlayer = playback ?: return
        applyingRemote = true
        applyingRemoteUntil = SystemClock.elapsedRealtime() + 1_500L
        try {
            val current = activePlayer.snapshot()
            if (!current.connected) {
                throw java.io.IOException("The playback service is still connecting. Retrying sync…")
            }

            if (current.sourceId != remote.sourceUrl) {
                status.text = "Loading the other device's track…"
                val audio = mediaResolver.resolve(remote.sourceId, remote.sourceUrl)
                // Prepare silently. The receiving phone does not start early while resolving.
                if (!activePlayer.play(audio, shouldPlay = false, userInitiated = false)) {
                    throw java.io.IOException("The player rejected the shared track.")
                }
                val readyDeadline = SystemClock.elapsedRealtime() + 20_000L
                while (SystemClock.elapsedRealtime() < readyDeadline) {
                    val state = activePlayer.snapshot().playbackState
                    if (state == Player.STATE_READY) break
                    if (state == Player.STATE_IDLE || state == Player.STATE_BUFFERING) {
                        delay(100L)
                    } else {
                        delay(50L)
                    }
                }
                if (activePlayer.snapshot().playbackState != Player.STATE_READY) {
                    throw java.io.IOException("The shared track is still buffering. Nexus will retry without restarting the other phone.")
                }
            }

            // A pause/skip may arrive while this phone is resolving a slow stream.
            // Never apply an older command after a newer one has already reached the server.
            val activeRoomId = roomId ?: return
            val newestRoomState = api.loadPlayback(activeRoomId)
            if (newestRoomState != null && newestRoomState.revision > remote.revision) {
                lastRemotePlayback = newestRoomState
                throw java.io.IOException(
                    "A newer playback action arrived while loading. Applying the latest room state…"
                )
            }

            // Use the server timestamp after the track is ready, so extraction/buffering time
            // is included and the receiver catches up instead of starting from an old position.
            val elapsed = parseTimestamp(remote.updatedAt)
                ?.let { (api.serverNowMillis() - it).coerceAtLeast(0L) } ?: 0L
            val expectedPosition = RoomPlaybackMath.expectedRemotePositionMs(
                positionMs = remote.positionMs,
                isPlaying = remote.isPlaying,
                elapsedSinceUpdateMs = elapsed,
                durationMs = remote.durationMs
            )
            val latest = activePlayer.snapshot()
            if (RoomPlaybackMath.needsSeek(latest.positionMs, expectedPosition) &&
                !activePlayer.seekTo(expectedPosition)
            ) {
                throw java.io.IOException("The player is not ready to seek yet. Retrying sync…")
            }
            activePlayer.resetPlaybackSpeed()
            activePlayer.setPlaying(remote.isPlaying, expectedPosition)
            rememberPublishedPlayback(activePlayer.snapshot())
            status.text = "Synced with the other device."
        } finally {
            applyingRemote = false
            applyingRemoteUntil = SystemClock.elapsedRealtime() + 1_500L
        }
    }

    private fun parseTimestamp(value: String): Long? {
        // Postgres may return microsecond precision; SimpleDateFormat's SSS pattern expects
        // milliseconds, so trim any extra fractional digits before parsing.
        val fractionPattern = Regex("(\\.\\d{3})\\d+(?=Z|[+-]\\d{2}:?\\d{2}$)")
        val normalized = fractionPattern.replace(value) { match -> match.groupValues[1] }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(normalized)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun leaveRoom() {
        val id = roomId ?: return
        scope.launch {
            val leaveFailed = runCatching { api.leaveRoom(id) }.isFailure
            exitRoom()
            if (leaveFailed) {
                status.text = "Exited on this phone. The server will release this seat automatically if the connection was interrupted."
            }
        }
    }

    private fun exitRoom() {
        syncJob?.cancel()
        syncJob = null
        driftJob?.cancel()
        driftJob = null
        lastRemotePlayback = null
        messageInput.text.clear()
        codeInput.text.clear()
        isSeeking = false
        applyingRemoteUntil = 0L
        applyingRemote = false
        realtime?.close()
        realtime = null
        realtimeConnected = false
        driftController.reset()
        remoteApplyRetryPolicy.reset()
        roomId = null
        inviteCode = null
        userId = null
        lastRemoteRevision = 0L
        lastPublishedSource = ""
        lastPublishedPlaying = false
        lastPublishedPositionMs = 0L
        lastPublishedAtElapsed = 0L
        lastPublishedActionSequence = -1L
        roomPanel.visibility = View.GONE
        roomActions.visibility = View.VISIBLE
        createButton.visibility = View.VISIBLE
        codeInput.visibility = View.VISIBLE
        joinButton.visibility = View.VISIBLE
        messagesBox.removeAllViews()
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
        syncJob?.cancel()
        driftJob?.cancel()
        realtime?.close()
        realtime = null
        handler.removeCallbacks(playerTicker)
        playback?.close()
        playback = null
        scope.cancel()
        super.onDestroy()
    }
}
