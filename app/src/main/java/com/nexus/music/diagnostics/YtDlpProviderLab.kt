package com.nexus.music.diagnostics

import android.content.Context

/** Debug-only yt-dlp experiment boundary. No production catalogue or playback wiring. */
internal interface YtDlpProviderLab {
    suspend fun initializeAndUpdate(context: Context): String
    suspend fun search(context: Context, query: String): YtDlpSearchResult
    suspend fun resolveAudio(context: Context, sourceUrl: String): YtDlpResolvedAudio
}

internal data class YtDlpSearchResult(
    val videoId: String,
    val title: String,
    val sourceUrl: String
)

internal data class YtDlpResolvedAudio(
    val title: String,
    val audioUrl: String
)
