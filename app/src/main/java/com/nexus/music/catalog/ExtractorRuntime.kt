package com.nexus.music.catalog

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader

/** NewPipe stores its downloader process-wide; the diagnostic lab swaps it only while running. */
internal object ExtractorRuntime {
    @Synchronized
    fun initialize(downloader: Downloader) {
        NewPipe.init(downloader)
    }
}
