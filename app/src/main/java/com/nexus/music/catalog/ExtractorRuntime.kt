package com.nexus.music.catalog

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader

/** Serializes access to NewPipe's process-wide downloader while providers are being switched. */
internal object ExtractorRuntime {
    @Synchronized
    fun <T> withDownloader(downloader: Downloader, block: () -> T): T {
        NewPipe.init(downloader)
        return block()
    }
}
