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
    override suspend fun initializeAndUpdate(context: Context): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        YoutubeDL.getInstance().init(appContext)
        YoutubeDL.getInstance().updateYoutubeDL(appContext)
        "success"
    }

    override suspend fun search(context: Context, query: String): YtDlpSearchResult = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Search query must not be blank." }
        val request = YoutubeDLRequest("ytsearch1:$query")
        request.addOption("--no-warnings")
        val info = YoutubeDL.getInstance().getInfo(request)
        val id = info.id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
            ?: throw IOException("yt-dlp search did not return a YouTube video ID.")
        YtDlpSearchResult(
            videoId = id,
            title = info.title.orEmpty(),
            sourceUrl = "https://www.youtube.com/watch?v=$id"
        )
    }

    override suspend fun resolveAudio(context: Context, sourceUrl: String): YtDlpResolvedAudio =
        withContext(Dispatchers.IO) {
            require(sourceUrl.startsWith("https://www.youtube.com/")) {
                "yt-dlp Provider Lab accepts canonical HTTPS YouTube URLs only."
            }
            val request = YoutubeDLRequest(sourceUrl)
            request.addOption("-f", "bestaudio/best")
            request.addOption("--js-runtimes", "quickjs")
            request.addOption("--remote-components", "ejs:github")
            val info = YoutubeDL.getInstance().getInfo(request)
            val url = info.url?.takeIf { it.startsWith("https://") }
                ?: throw IOException("yt-dlp did not return a directly playable HTTPS stream.")
            YtDlpResolvedAudio(
                title = info.title.orEmpty(),
                audioUrl = url
            )
        }
}
