package com.nexus.music.catalog

import com.nexus.music.performance.NexusPerformance
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader

/** Serializes access to NewPipe's process-wide downloader while providers are being switched. */
internal object ExtractorRuntime {
    fun <T> withDownloader(downloader: Downloader, block: () -> T): T {
        val waitStarted = NexusPerformance.nowMs()
        return synchronized(this) {
            val acquiredAt = NexusPerformance.nowMs()
            NexusPerformance.logEvent("newpipe_lock_acquired",
                "wait_ms=" + (acquiredAt - waitStarted))
            NewPipe.init(downloader)
            try {
                block()
            } finally {
                NexusPerformance.logEvent("newpipe_lock_released",
                    "hold_ms=" + (NexusPerformance.nowMs() - acquiredAt))
            }
        }
    }
}
