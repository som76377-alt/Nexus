package com.nexus.music.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nexus.music.catalog.YoutubeCatalogRepository
import com.nexus.music.rooms.ServiceRoomController
import com.nexus.music.rooms.YoutubeRoomMediaResolver
import com.nexus.music.rooms.RoomsApi
import com.nexus.music.rooms.data.SupabaseRoomClock
import com.nexus.music.rooms.data.SupabaseRoomRepository
import com.nexus.music.rooms.domain.RoomController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Owns the one ExoPlayer, its MediaSession and the room coordinator.
 * Room synchronization is not tied to ListeningRoomActivity's lifetime.
 */
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var roomPlayerPort: Media3RoomPlayerPort? = null
    private var roomController: ServiceRoomController? = null
    private var roomsApi: RoomsApi? = null
    private lateinit var serviceScope: CoroutineScope

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 Nexus/0.1"

        private val activeRoomController = MutableStateFlow<RoomController?>(null)
        private val activeRoomsApi = MutableStateFlow<RoomsApi?>(null)

        /** Process-local UI bridge; the controller and authenticated API remain service-owned. */
        fun currentRoomController(): RoomController? = activeRoomController.value

        suspend fun awaitRoomController(): RoomController =
            withTimeoutOrNull(SERVICE_BRIDGE_TIMEOUT_MS) {
                activeRoomController.filterNotNull().first()
            } ?: throw IOException("Playback service did not finish starting the room controller.")

        suspend fun awaitRoomsApi(): RoomsApi =
            withTimeoutOrNull(SERVICE_BRIDGE_TIMEOUT_MS) {
                activeRoomsApi.filterNotNull().first()
            } ?: throw IOException("Playback service did not finish starting the room API.")

        private const val SERVICE_BRIDGE_TIMEOUT_MS = 10_000L
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val musicAudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                setAudioAttributes(musicAudioAttributes, /* handleAudioFocus = */ true)
                setHandleAudioBecomingNoisy(true)
            }

        player = exoPlayer
        val playerPort = Media3RoomPlayerPort(exoPlayer)
        roomPlayerPort = playerPort

        val roomsApi = RoomsApi(this)
        this.roomsApi = roomsApi
        activeRoomsApi.value = roomsApi
        val repository = SupabaseRoomRepository(roomsApi)
        val clock = SupabaseRoomClock(roomsApi)
        val mediaResolver = YoutubeRoomMediaResolver(YoutubeCatalogRepository())
        val controller = ServiceRoomController(
            parentScope = serviceScope,
            repository = repository,
            player = playerPort,
            clock = clock,
            mediaResolver = mediaResolver
        )
        roomController = controller
        activeRoomController.value = controller

        val sessionPlayer = RoomIntentForwardingPlayer(
            delegate = exoPlayer,
            recordIntent = playerPort::recordUserIntent,
            isRoomActive = { controller.state.value.roomId != null }
        )
        mediaSession = MediaSession.Builder(this, sessionPlayer).build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    // Keep Media3's platform-default task-removal behavior. Android stops a paused
    // MediaSessionService; ListeningRoomActivity persists the opaque room UUID and reattaches
    // after the app is reopened. Do not claim an indefinitely running paused service.

    override fun onDestroy() {
        if (activeRoomController.value === roomController) {
            activeRoomController.value = null
        }
        if (activeRoomsApi.value === roomsApi) {
            activeRoomsApi.value = null
        }
        roomsApi = null
        roomController?.close()
        roomController = null
        serviceScope.cancel()
        mediaSession?.release()
        roomPlayerPort?.close()
        player?.release()
        mediaSession = null
        roomPlayerPort = null
        player = null
        super.onDestroy()
    }
}
