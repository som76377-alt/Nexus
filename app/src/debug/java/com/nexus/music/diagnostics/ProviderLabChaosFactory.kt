package com.nexus.music.diagnostics

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.nexus.music.catalog.CatalogTrack
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import com.nexus.music.catalog.ProviderHttpException
import com.nexus.music.catalog.ResolvedAudio
import com.nexus.music.catalog.StreamProvider
import com.nexus.music.catalog.StreamProviderAdapter
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import com.nexus.music.catalog.YoutubeSearchScope
import kotlinx.coroutines.CancellationException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

internal object ProviderLabChaosFactory {
    fun create(): ProviderLabChaos? = DebugProviderLabChaos
}

private object DebugProviderLabChaos : ProviderLabChaos {
    override fun wrap(
        adapter: StreamProviderAdapter,
        provider: StreamProvider,
        failure: ProviderLabFailure
    ): StreamProviderAdapter {
        if (failure == ProviderLabFailure.NONE) return adapter
        return object : StreamProviderAdapter {
            override val provider: StreamProvider = adapter.provider

            private fun failIfSelected() {
                if (adapter.provider != provider) return
                when (failure) {
                    ProviderLabFailure.NONE -> Unit
                    ProviderLabFailure.BOT_CHECK ->
                        throw ReCaptchaException("Provider Lab forced bot-check challenge.", "https://www.youtube.com/")
                    ProviderLabFailure.RATE_LIMITED ->
                        throw ProviderHttpException(429, provider.key, 60_000L)
                    ProviderLabFailure.EXTRACTOR_BROKEN ->
                        throw IllegalStateException("Provider Lab forced extractor failure.")
                    ProviderLabFailure.NETWORK ->
                        throw IOException("Provider Lab forced network failure.")
                    ProviderLabFailure.CONTENT_UNAVAILABLE ->
                        throw ContentNotAvailableException("Provider Lab forced content-unavailable response.")
                    ProviderLabFailure.STREAM_FORBIDDEN ->
                        throw ProviderHttpException(403, provider.key)
                }
            }

            override suspend fun search(query: String, scope: YoutubeSearchScope): List<CatalogTrack> {
                failIfSelected()
                return adapter.search(query, scope)
            }

            override suspend fun resolveAudio(videoUrl: String): ResolvedAudio {
                failIfSelected()
                return adapter.resolveAudio(videoUrl)
            }
        }
    }

    override fun wrapMediaDataSource(
        upstream: DataSource.Factory,
        force403AfterSeconds: Int
    ): DataSource.Factory {
        if (force403AfterSeconds <= 0) return upstream
        return DataSource.Factory {
            Timed403DataSource(upstream.createDataSource(), force403AfterSeconds)
        }
    }
}

private class Timed403DataSource(
    private val upstream: DataSource,
    private val seconds: Int
) : DataSource {
    private var openedAtNanos = 0L
    private var forced = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        openedAtNanos = System.nanoTime()
        forced = false
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!forced && System.nanoTime() - openedAtNanos >= seconds * 1_000_000_000L) {
            forced = true
            throw ForcedStream403Exception(seconds)
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}

internal class ForcedStream403Exception(seconds: Int) :
    IOException("Provider Lab forced HTTP 403 after $seconds seconds.")
