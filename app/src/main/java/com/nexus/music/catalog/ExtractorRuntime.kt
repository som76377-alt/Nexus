package com.nexus.music.catalog

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader

/**
 * NewPipe Extractor keeps its downloader in process-wide static state.
 * Initialize it once, before any extractor calls.
 */
internal object ExtractorRuntime {
    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(downloader: Downloader) {
        if (!initialized) {
            NewPipe.init(downloader)
            initialized = true
        }
    }
}
