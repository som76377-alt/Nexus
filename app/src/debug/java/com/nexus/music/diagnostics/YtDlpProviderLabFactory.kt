package com.nexus.music.diagnostics

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal object YtDlpProviderLabFactory {
    fun create(): YtDlpProviderLab = AndroidYtDlpProviderLab
}

private object AndroidYtDlpProviderLab : YtDlpProviderLab {
    override suspend fun initializeRuntime(context: Context): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        YoutubeDL.getInstance().init(appContext)
        val cachedVersion = YoutubeDL.getInstance().versionName(appContext)
        if (cachedVersion.isNullOrBlank()) "bundled runtime · network update skipped"
        else "cached runtime $cachedVersion · network update skipped"
    }

    override suspend fun resolveAudio(context: Context, sourceUrl: String): YtDlpResolvedAudio =
        withContext(Dispatchers.IO) {
            require(sourceUrl.startsWith("https://www.youtube.com/")) {
                "yt-dlp Provider Lab accepts canonical HTTPS YouTube URLs only."
            }
            val request = YoutubeDLRequest(sourceUrl)
            request.addOption("-f", "bestaudio/best")
            request.addOption("--js-runtimes", "quickjs")
            val info = YoutubeDL.getInstance().getInfo(request)
            val id = info.id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                ?: throw IOException("yt-dlp did not return a valid YouTube video ID.")
            val url = info.url?.takeIf { it.startsWith("https://") }
                ?: throw IOException("yt-dlp did not return a directly playable HTTPS stream.")
            YtDlpResolvedAudio(
                videoId = id,
                title = info.title.orEmpty(),
                audioUrl = url,
                httpHeaders = info.httpHeaders.orEmpty()
            )
        }
}
