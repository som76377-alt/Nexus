package com.nexus.music.playback

import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nexus.music.BuildConfig
import com.nexus.music.catalog.NexusCatalog
import com.nexus.music.catalog.StreamAddressFamily
import com.nexus.music.catalog.StreamProvider
import com.nexus.music.rooms.RoomsApi
import com.nexus.music.rooms.ServiceRoomController
import com.nexus.music.rooms.YoutubeRoomMediaResolver
import com.nexus.music.rooms.data.SupabaseRoomClock
import com.nexus.music.rooms.data.SupabaseRoomRepository
import com.nexus.music.rooms.domain.RoomController
import com.nexus.music.rooms.domain.RoomDiagnosticsDeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Owns the one ExoPlayer, its MediaSession and the room coordinator.
 * Room synchronization is not tied to ListeningRoomActivity's lifetime.
 */
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
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
        private const val PLAYER_WAKE_MODE = C.WAKE_MODE_NETWORK
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val musicAudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val playbackHttpClient = OkHttpClient.Builder()
            .dns(StreamAddressFamily.dns)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder().header("User-Agent", USER_AGENT).build()
                )
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .build()
        val httpDataSourceFactory = OkHttpDataSource.Factory(playbackHttpClient)
        val perStreamHeadersFactory = ResolvingDataSource.Factory(httpDataSourceFactory) { dataSpec ->
            StreamRequestHeaders.resolve(dataSpec)
        }
        val cachedDataSourceFactory = AudioCacheStore.dataSourceFactory(
            this,
            perStreamHeadersFactory
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(cachedDataSourceFactory)
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            // Keep CPU and Wi-Fi available for streaming while background playback is active.
            .setWakeMode(PLAYER_WAKE_MODE)
            .build()
            .apply {
                setAudioAttributes(musicAudioAttributes, /* handleAudioFocus = */ true)
                setHandleAudioBecomingNoisy(true)
            }

        player = exoPlayer
        val catalog = NexusCatalog.repository(this)
        val localStreamRecovery = LocalStreamRecovery(
            resolveNext = catalog::resolveAudioExcluding,
            isCurrentSource = { sourceUrl -> exoPlayer.currentMediaItem?.mediaId == sourceUrl },
            replaceLocally = { replacement, resumePositionMs, resumeWhenReady ->
                StreamRequestHeaders.replace(
                    replacement.audioUrl,
                    replacement.requestHeaders,
                    replacement.provider
                )
                val replacementItem = MediaItem.Builder()
                    .setMediaId(replacement.sourceUrl)
                    .setUri(replacement.audioUrl)
                    .setCustomCacheKey(AudioCachePolicy.cacheKey(replacement.sourceUrl, replacement.audioUrl))
                    .setMimeType(replacement.mimeType)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(replacement.title)
                            .setArtist(replacement.artist.orEmpty())
                            .setArtworkUri(replacement.thumbnailUrl?.let(android.net.Uri::parse))
                            .build()
                    )
                    .build()
                // Local-only repair: mutate the raw ExoPlayer, never the room-intent wrapper.
                exoPlayer.setMediaItem(replacementItem, resumePositionMs)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = resumeWhenReady
            }
        )
        val failedProvidersBySource = mutableMapOf<String, MutableSet<StreamProvider>>()
        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val currentSource = mediaItem?.mediaId.orEmpty()
                failedProvidersBySource.keys.removeAll { it != currentSource }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val failedItem = exoPlayer.currentMediaItem ?: return
                val sourceUrl = failedItem.mediaId.takeIf(String::isNotBlank) ?: return
                val failedStreamUrl = failedItem.localConfiguration?.uri?.toString() ?: return
                val failedProvider = StreamRequestHeaders.providerForUrl(failedStreamUrl) ?: return
                val attempted = failedProvidersBySource.getOrPut(sourceUrl) { mutableSetOf() }
                if (!attempted.add(failedProvider)) return
                val resumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                val resumeWhenReady = exoPlayer.playWhenReady
                val title = failedItem.mediaMetadata.title?.toString().orEmpty()
                val artist = failedItem.mediaMetadata.artist?.toString()
                val artworkUri = failedItem.mediaMetadata.artworkUri
                serviceScope.launch {
                    try {
                        localStreamRecovery.recover(
                            sourceUrl = sourceUrl,
                            excludedProviders = attempted.toSet(),
                            resumePositionMs = resumePositionMs,
                            resumeWhenReady = resumeWhenReady
                        )
                    } catch (_: Exception) {
                        // Exhausted providers leave the original error visible; never loop
                        // on the same provider or publish a room recovery command.
                    }
                }
            }
        })
        val playerPort = Media3RoomPlayerPort(exoPlayer)

        val roomsApi = RoomsApi(this)
        this.roomsApi = roomsApi
        activeRoomsApi.value = roomsApi
        val repository = SupabaseRoomRepository(roomsApi)
        val clock = SupabaseRoomClock(roomsApi)
        val mediaResolver = YoutubeRoomMediaResolver(catalog)
        val controller = ServiceRoomController(
            parentScope = serviceScope,
            repository = repository,
            player = playerPort,
            clock = clock,
            mediaResolver = mediaResolver,
            diagnosticsDeviceInfo = RoomDiagnosticsDeviceInfo(
                appVersion = BuildConfig.VERSION_NAME,
                deviceModel = Build.MODEL,
                apiLevel = Build.VERSION.SDK_INT,
                wakeMode = PLAYER_WAKE_MODE
            )
        )
        roomController = controller
        activeRoomController.value = controller

        val sessionPlayer = RoomIntentForwardingPlayer(
            delegate = exoPlayer,
            recordIntent = playerPort::recordUserIntent,
            playbackPhase = controller::playbackPhase
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
        player?.release()
        mediaSession = null
        player = null
        super.onDestroy()
    }
}
