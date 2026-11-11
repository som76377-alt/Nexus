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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nexus.music.BuildConfig
import com.nexus.music.catalog.OkHttpDownloader
import com.nexus.music.catalog.YoutubeCatalogRepository
import com.nexus.music.catalog.YoutubeSearchScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Hidden, opt-in diagnostic. It deliberately performs real network and audible playback tests. */
class ProviderLabActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var report: TextView
    private lateinit var runButton: Button
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Provider Lab"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply { text = "Tests five fixed YouTube samples using the current NewPipe provider. Each successful cell plays audio for 10 seconds." }
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
        lifecycleScope.launch {
            val rows = mutableListOf<ProviderLabReportFormatter.Cell>()
            val network = networkSummary()
            status.text = "Running 15 checks. Keep Nexus open; successful checks play for 10 seconds."
            try {
                for (sample in SAMPLES) {
                    for (family in FamilyMode.entries) {
                        status.text = "NewPipe · ${sample.label} · ${family.label}"
                        rows += runCell(sample, family)
                        report.text = ProviderLabReportFormatter.format(
                            BuildConfig.VERSION_NAME, network, rows
                        )
                    }
                }
            } finally {
                report.text = ProviderLabReportFormatter.format(BuildConfig.VERSION_NAME, network, rows)
                status.text = "Finished · ${rows.count { it.ok }}/${rows.size} checks passed. Copy the report and share it."
                runButton.isEnabled = true
                running = false
            }
        }
    }

    private suspend fun runCell(sample: Sample, family: FamilyMode): ProviderLabReportFormatter.Cell {
        val started = System.nanoTime()
        var httpCode: Int? = null
        return try {
            val client = clientFor(family)
            val repository = withContext(Dispatchers.IO) {
                YoutubeCatalogRepository(OkHttpDownloader(client))
            }
            val found = repository.search(sample.id, YoutubeSearchScope.EVERYTHING)
                .firstOrNull { videoId(it.url) == sample.id }
                ?: throw IllegalStateException("Search did not return the fixed sample ID")
            val audio = repository.resolveAudio(found.url)

            httpCode = withContext(Dispatchers.IO) {
                client.newCall(
                    Request.Builder().url(audio.audioUrl).header("Range", "bytes=0-1023").build()
                ).execute().use { response ->
                    val code = response.code
                    if (!response.isSuccessful) throw ProviderHttpException(code, "Audio probe rejected")
                    val bytes = response.body?.byteStream()?.use { stream ->
                        val buffer = ByteArray(1024)
                        val count = stream.read(buffer)
                        if (count > 0) buffer.copyOf(count) else byteArrayOf()
                    } ?: byteArrayOf()
                    if (bytes.isEmpty()) throw ProviderHttpException(code, "Audio probe returned no bytes")
                    code
                }
            }
            playForTenSeconds(audio.audioUrl, client)
            ProviderLabReportFormatter.Cell(
                sample.id, family.label, true, null, httpCode,
                elapsedMs(started)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error is ProviderHttpException) httpCode = error.code
            ProviderLabReportFormatter.Cell(
                sample.id, family.label, false,
                error::class.java.simpleName + error.message?.let { ": ${it.take(90)}" }.orEmpty(),
                httpCode, elapsedMs(started)
            )
        } finally {
            // Restore the ordinary downloader after the diagnostic route override.
            withContext(Dispatchers.IO) { YoutubeCatalogRepository() }
        }
    }

    private suspend fun playForTenSeconds(url: String, client: OkHttpClient) {
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client)))
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
            var beganAt: Long? = null
            while (System.nanoTime() < deadline) {
                player.playerError?.let { throw IllegalStateException(it.errorCodeName + ": " + it.message) }
                if (player.isPlaying) {
                    if (beganAt == null) beganAt = System.nanoTime()
                    if (System.nanoTime() - beganAt >= TimeUnit.SECONDS.toNanos(10)) return
                }
                delay(100)
            }
            throw IllegalStateException("Media3 did not sustain 10 seconds of playback")
        } finally {
            player.release()
        }
    }

    private fun clientFor(mode: FamilyMode): OkHttpClient {
        val dns = if (mode == FamilyMode.DEFAULT) Dns.SYSTEM else Dns { host ->
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
            addresses
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
    private class ProviderHttpException(val code: Int, message: String) : java.io.IOException(message)

    companion object {
        // Fixed IDs: two high-traffic uploads, one long-form upload and two live-stream samples.
        private val SAMPLES = listOf(
            Sample("dQw4w9WgXcQ", "popular"),
            Sample("kJQP7kiw5Fk", "popular-2"),
            Sample("3JZ_D3ELwOQ", "long-form"),
            Sample("jfKfPfyJRdk", "live"),
            Sample("5qap5aO4i9A", "live-2")
        )
    }
}
