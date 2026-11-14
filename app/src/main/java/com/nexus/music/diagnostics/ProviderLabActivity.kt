package com.nexus.music.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.os.Bundle
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nexus.music.BuildConfig
import com.nexus.music.catalog.OkHttpDownloader
import com.nexus.music.catalog.InvidiousStreamProviderAdapter
import com.nexus.music.catalog.PipedStreamProviderAdapter
import com.nexus.music.catalog.ProviderResolverChain
import com.nexus.music.catalog.SharedPreferencesProviderHealthStore
import com.nexus.music.catalog.StreamProvider
import com.nexus.music.catalog.StreamProviderAdapter
import com.nexus.music.catalog.StreamProbe
import com.nexus.music.catalog.sanitizeStreamRequestHeaders
import com.nexus.music.catalog.ProviderInstanceDiscovery
import com.nexus.music.catalog.ProviderHttpException as CatalogProviderHttpException
import com.nexus.music.catalog.ExtractorRuntime
import com.nexus.music.catalog.NewPipeStreamProviderAdapter
import com.nexus.music.catalog.YoutubeSearchScope
import com.nexus.music.playback.AudioCacheStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Hidden, opt-in diagnostic. It deliberately performs real network and audible playback tests. */
class ProviderLabActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var report: TextView
    private lateinit var runButton: Button
    private lateinit var providerSpinner: Spinner
    private lateinit var failureSpinner: Spinner
    private lateinit var stream403Seconds: EditText
    private val healthStore by lazy { SharedPreferencesProviderHealthStore(this) }
    private val instanceDiscovery by lazy { ProviderInstanceDiscovery(this) }
    private val chaos by lazy { if (BuildConfig.DEBUG) ProviderLabChaosFactory.create() else null }
    private var running = false
    private val labScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Provider Lab"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply {
            text = "Tests five fixed YouTube samples across default, IPv4-only and IPv6-only networking. Stream probes and playback use provider-supplied request headers; successful cells play audio for 10 seconds."
        }
        providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ProviderLabActivity,
                android.R.layout.simple_spinner_dropdown_item,
                StreamProvider.entries.map { it.key }
            )
        }
        failureSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ProviderLabActivity,
                android.R.layout.simple_spinner_dropdown_item,
                ProviderLabFailure.entries.map { it.name }
            )
        }
        stream403Seconds = EditText(this).apply {
            hint = "Force stream HTTP 403 after seconds (0 = off)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("0")
        }
        runButton = Button(this).apply {
            text = "Run Provider Lab"
            setOnClickListener { runLab() }
        }
        report = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        val copy = Button(this).apply {
            text = "Copy report"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Nexus Provider Lab", report.text))
                status.text = "Report copied."
            }
        }
        root.addView(status)
        if (BuildConfig.DEBUG && chaos != null) {
            root.addView(TextView(this).apply { text = "Provider" })
            root.addView(providerSpinner)
            root.addView(TextView(this).apply { text = "Force provider failure (debug only)" })
            root.addView(failureSpinner)
            root.addView(stream403Seconds)
        }
        root.addView(runButton)
        root.addView(copy)
        root.addView(ScrollView(this).apply { addView(report) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun runLab() {
        if (running) return
        running = true
        runButton.isEnabled = false
        report.text = ""
        labScope.launch {
            val rows = mutableListOf<ProviderLabReportFormatter.Cell>()
            val network = networkSummary()
            val provider = StreamProvider.entries.first { it.key == providerSpinner.selectedItem.toString() }
            val failure = if (BuildConfig.DEBUG) {
                ProviderLabFailure.valueOf(failureSpinner.selectedItem.toString())
            } else ProviderLabFailure.NONE
            val forced403Seconds = if (BuildConfig.DEBUG) {
                stream403Seconds.text.toString().toIntOrNull()?.coerceIn(0, 300) ?: 0
            } else 0
            status.text = "Refreshing Piped and Invidious instance directories..."
            try {
                instanceDiscovery.refreshIfStale { _, _ -> }
                status.text = "Running 15 checks · ${provider.key} · failure=${failure.name} · stream403=${forced403Seconds}s"
                for (sample in SAMPLES) {
                    for (family in FamilyMode.entries) {
                        status.text = "${provider.key} · ${sample.label} · ${family.label}"
                        rows += runCell(sample, family, provider, failure, forced403Seconds)
                        report.text = formatReport(network, rows)
                    }
                }
            } finally {
                report.text = formatReport(network, rows)
                status.text = "Finished · ${rows.count { it.ok }}/${rows.size} checks passed. Copy the report and share it."
                runButton.isEnabled = true
                running = false
            }
        }
    }

    private suspend fun runCell(sample: Sample, family: FamilyMode, provider: StreamProvider, failure: ProviderLabFailure, forced403Seconds: Int): ProviderLabReportFormatter.Cell {
        val started = System.nanoTime()
        var httpCode: Int? = null
        return try {
            val client = clientFor(family)
            val baseAdapter = adapterFor(provider, client)
            val testAdapter = chaos?.wrap(baseAdapter, provider, failure) ?: baseAdapter
            val resolver = ProviderResolverChain(
                adapters = listOf(testAdapter),
                healthStore = healthStore,
                providerOrder = listOf(provider),
                hedgeDelayMs = 0L
            )
            val searchWarning = try {
                val found = resolver.search(sample.id, YoutubeSearchScope.EVERYTHING)
                    .any { videoId(it.url) == sample.id }
                if (found) null else "Search did not return the fixed sample ID"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                "Search failed: ${error.message.orEmpty().take(60)}"
            }
            // Search indexing is independent of direct stream resolution. Always probe the
            // known sample URL so a search miss cannot hide a working resolver/stream path.
            val audio = resolver.resolveAudio("https://www.youtube.com/watch?v=${sample.id}")

            StreamProbe(client).validate(audio) { responseCode ->
                httpCode = responseCode
            }
            playForTenSeconds(audio.audioUrl, client, audio.requestHeaders, forced403Seconds)
            ProviderLabReportFormatter.Cell(
                provider.key, sample.id, family.label, true, null, httpCode,
                elapsedMs(started), searchWarning
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error is CatalogProviderHttpException) httpCode = error.statusCode
            if (generateSequence(error) { it.cause }.any { it.message?.contains("forced HTTP 403") == true }) {
                httpCode = 403
            }
            ProviderLabReportFormatter.Cell(
                provider.key, sample.id, family.label, false,
                error::class.java.simpleName + error.message?.let { ": ${it.take(240)}" }.orEmpty(),
                httpCode, elapsedMs(started)
            )
        } finally {
            // Restore the ordinary downloader after the diagnostic route override.
            withContext(NonCancellable + Dispatchers.IO) {
                ExtractorRuntime.withDownloader(OkHttpDownloader()) { Unit }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun playForTenSeconds(
        url: String,
        client: OkHttpClient,
        requestHeaders: Map<String, String>,
        forced403Seconds: Int
    ) {
        val dataSourceFactory = OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(sanitizeStreamRequestHeaders(requestHeaders))
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(chaos?.wrapMediaDataSource(dataSourceFactory, forced403Seconds) ?: dataSourceFactory))
            .build()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true
            )
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.play()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
            var firstPlayingPositionMs: Long? = null
            var furthestPositionMs = 0L
            while (System.nanoTime() < deadline) {
                player.playerError?.let { throw IllegalStateException(it.errorCodeName + ": " + it.message) }
                if (player.isPlaying) {
                    if (firstPlayingPositionMs == null) {
                        firstPlayingPositionMs = player.currentPosition
                        furthestPositionMs = player.currentPosition
                    } else {
                        furthestPositionMs = maxOf(furthestPositionMs, player.currentPosition)
                    }
                    // Measure media-position progress, not uninterrupted isPlaying wall time.
                    // Brief network rebuffering must not turn a progressing stream into a false failure.
                    if (furthestPositionMs - firstPlayingPositionMs >= 10_000L) return
                }
                delay(100)
            }
            throw IllegalStateException(
                "Media3 progress timeout: state=${player.playbackState}, " +
                    "playWhenReady=${player.playWhenReady}, isPlaying=${player.isPlaying}, " +
                    "positionMs=${player.currentPosition}, bufferedMs=${player.bufferedPosition}, " +
                    "durationMs=${player.duration}, loading=${player.isLoading}"
            )
        } finally {
            player.release()
        }
    }

    private fun adapterFor(provider: StreamProvider, client: OkHttpClient): StreamProviderAdapter {
        return when (provider) {
            StreamProvider.NEWPIPE -> NewPipeStreamProviderAdapter(OkHttpDownloader(client))
            StreamProvider.PIPED -> PipedStreamProviderAdapter(
                (BuildConfig.PIPED_API_ENDPOINTS.split(',').filter(String::isNotBlank) +
                    instanceDiscovery.cachedEndpoints(StreamProvider.PIPED)).distinct(),
                client
            )
            StreamProvider.INVIDIOUS -> InvidiousStreamProviderAdapter(
                (BuildConfig.INVIDIOUS_API_ENDPOINTS.split(',').filter(String::isNotBlank) +
                    instanceDiscovery.cachedEndpoints(StreamProvider.INVIDIOUS)).distinct(),
                client
            )
        }
    }

    private fun formatReport(
        network: String,
        cells: List<ProviderLabReportFormatter.Cell>
    ): String {
        val now = System.currentTimeMillis()
        val health = StreamProvider.entries.map { provider ->
            val value = healthStore.get(provider)
            ProviderLabReportFormatter.ProviderHealthRow(
                provider = provider.key,
                consecutiveFailures = value.consecutiveFailures,
                lastSuccessEpochMs = value.lastSuccessEpochMs,
                lastError = value.lastErrorClass?.name,
                cooldownRemainingMs = (value.cooldownUntilEpochMs - now).coerceAtLeast(0L)
            )
        }
        val cacheBytes = runCatching { AudioCacheStore.usedBytes(this) }.getOrDefault(0L)
        val pipedInstances = (BuildConfig.PIPED_API_ENDPOINTS.split(',') + instanceDiscovery.cachedEndpoints(StreamProvider.PIPED))
            .map(String::trim).filter(String::isNotEmpty).distinct().size
        val invidiousInstances = (BuildConfig.INVIDIOUS_API_ENDPOINTS.split(',') + instanceDiscovery.cachedEndpoints(StreamProvider.INVIDIOUS))
            .map(String::trim).filter(String::isNotEmpty).distinct().size
        val config = "local-v0 · order=${BuildConfig.STREAM_PROVIDER_ORDER} · piped-instances=$pipedInstances · invidious-instances=$invidiousInstances · piped-discovery=${instanceDiscovery.lastDiscoveryStatus(StreamProvider.PIPED)} · invidious-discovery=${instanceDiscovery.lastDiscoveryStatus(StreamProvider.INVIDIOUS)}"
        return ProviderLabReportFormatter.format(
            appVersion = BuildConfig.VERSION_NAME,
            flavor = if (BuildConfig.DEBUG) "debug" else "release",
            network = network,
            cacheBytes = cacheBytes,
            configVersion = config,
            providerHealth = health,
            cells = cells
        )
    }

    private fun clientFor(mode: FamilyMode): OkHttpClient {
        val dns = if (mode == FamilyMode.DEFAULT) Dns.SYSTEM else object : Dns {
            override fun lookup(host: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(host).filter {
                    when (mode) {
                        FamilyMode.IPV4 -> it is Inet4Address
                        FamilyMode.IPV6 -> it is Inet6Address
                        FamilyMode.DEFAULT -> true
                    }
                }
                if (addresses.isEmpty()) throw java.net.UnknownHostException(
                    "No ${mode.label} address available for $host"
                )
                return addresses
            }
        }
        return OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .build()
    }

    override fun onDestroy() {
        labScope.cancel()
        super.onDestroy()
    }

    private fun networkSummary(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let(cm::getNetworkCapabilities)
        val links: LinkProperties? = network?.let(cm::getLinkProperties)
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            else -> "other/offline"
        }
        val addresses = links?.linkAddresses.orEmpty().map { it.address }
        val v4 = addresses.any { it is Inet4Address }
        val v6 = addresses.any { it is Inet6Address && !it.isLinkLocalAddress }
        return "$transport · IPv4=${if (v4) "yes" else "no"} · IPv6=${if (v6) "yes" else "no"}"
    }

    private fun videoId(url: String): String? = Regex("(?:v=|youtu\\.be/)([A-Za-z0-9_-]{11})")
        .find(url)?.groupValues?.getOrNull(1)

    private fun elapsedMs(started: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private data class Sample(val id: String, val label: String)
    private enum class FamilyMode(val label: String) { DEFAULT("default"), IPV4("IPv4-only"), IPV6("IPv6-only") }

    companion object {
        // Fixed IDs cover a popular upload, an obscure upload, long-form video and two live streams.
        private val SAMPLES = listOf(
            Sample("dQw4w9WgXcQ", "popular"),
            Sample("zPGf4liO-KQ", "obscure"),
            Sample("aqz-KE-bpKQ", "long-form"),
            Sample("jfKfPfyJRdk", "live"),
            Sample("5qap5aO4i9A", "live-2")
        )
    }
}
