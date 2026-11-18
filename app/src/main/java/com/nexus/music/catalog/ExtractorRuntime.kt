package com.nexus.music.catalog

import com.nexus.music.performance.NexusPerformance
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader

/** Serializes access to NewPipe's process-wide downloader while providers are being switched. */
internal object ExtractorRuntime {
    private val extractionLock = Any()

    fun <T> withDownloader(downloader: Downloader, block: () -> T): T {
        val waitStarted = System.nanoTime()
        return synchronized(extractionLock) {
            val acquiredAt = System.nanoTime()
            NexusPerformance.logEvent("newpipe_lock_acquired",
                "wait_ms=" + ((acquiredAt - waitStarted) / 1_000_000L))
            NewPipe.init(downloader)
            try {
                block()
            } finally {
                NexusPerformance.logEvent("newpipe_lock_released",
                    "hold_ms=" + ((System.nanoTime() - acquiredAt) / 1_000_000L))
            }
        }
    }
}
