package com.nexus.music.diagnostics

import androidx.media3.datasource.DataSource
import com.nexus.music.catalog.StreamProvider
import com.nexus.music.catalog.StreamProviderAdapter

internal enum class ProviderLabFailure {
    NONE,
    BOT_CHECK,
    RATE_LIMITED,
    EXTRACTOR_BROKEN,
    NETWORK,
    CONTENT_UNAVAILABLE,
    STREAM_FORBIDDEN
}

internal interface ProviderLabChaos {
    fun wrap(adapter: StreamProviderAdapter, provider: StreamProvider, failure: ProviderLabFailure): StreamProviderAdapter
    fun wrapMediaDataSource(upstream: DataSource.Factory, force403AfterSeconds: Int): DataSource.Factory
}
