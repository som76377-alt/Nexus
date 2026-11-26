package com.nexus.music.catalog

import com.nexus.music.performance.NexusPerformance
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader

/** Serializes access to NewPipe's process-wide downloader while providers are being switched. */
internal object ExtractorRuntime {
    private val activeTrace = ThreadLocal<NexusPerformance.Trace?>()

    fun currentTrace(): NexusPerformance.Trace? = activeTrace.get()

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
            val previousTrace = activeTrace.get()
            try {
                if (tracing && trace != null) activeTrace.set(trace) else activeTrace.remove()
                NewPipe.init(downloader)
                block()
            } finally {
                if (previousTrace == null) activeTrace.remove() else activeTrace.set(previousTrace)
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
