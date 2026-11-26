package com.nexus.music.catalog

import com.nexus.music.performance.NexusPerformance
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader

/** Serializes access to NewPipe's process-wide downloader while providers are being switched. */
internal object ExtractorRuntime {
    fun <T> withDownloader(
        downloader: Downloader,
        trace: NexusPerformance.Trace? = null,
        block: () -> T
    ): T {
        val tracing = NexusPerformance.isEnabled()
        val waitStarted = if (tracing) NexusPerformance.nowMs() else 0L
        return synchronized(this) {
            val acquiredAt = if (tracing) NexusPerformance.nowMs() else 0L
            if (tracing) {
                val waitMs = acquiredAt - waitStarted
                if (trace != null) {
                    NexusPerformance.event(trace, "newpipe_lock_acquired", "wait_ms=" + waitMs)
                } else {
                    NexusPerformance.logEvent("newpipe_lock_acquired", "wait_ms=" + waitMs)
                }
            }
            NewPipe.init(downloader)
            try {
                block()
            } finally {
                if (tracing) {
                    val holdMs = NexusPerformance.nowMs() - acquiredAt
                    if (trace != null) {
                        NexusPerformance.event(trace, "newpipe_lock_released", "hold_ms=" + holdMs)
                    } else {
                        NexusPerformance.logEvent("newpipe_lock_released", "hold_ms=" + holdMs)
                    }
                }
            }
        }
    }
}
