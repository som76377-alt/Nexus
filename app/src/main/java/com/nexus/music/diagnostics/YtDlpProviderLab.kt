package com.nexus.music.diagnostics

import android.content.Context

/** Debug-only yt-dlp experiment boundary. No production catalogue or playback wiring. */
internal interface YtDlpProviderLab {
    suspend fun initializeAndUpdate(context: Context): String
    suspend fun resolveAudio(context: Context, sourceUrl: String): YtDlpResolvedAudio
}

internal data class YtDlpResolvedAudio(
    val videoId: String,
    val title: String,
    val audioUrl: String,
    val httpHeaders: Map<String, String>
)
