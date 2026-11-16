package com.nexus.music.catalog

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import java.util.concurrent.locks.ReentrantLock

/**
 * Serializes access to NewPipe's process-wide downloader while providers are being switched.
 *
 * ReentrantLock.lockInterruptibly() matters here: a cancelled search/play request must be able
 * to leave the queue before it reaches NewPipe's process-wide critical section.
 */
internal object ExtractorRuntime {
    private val lock = ReentrantLock()

    fun <T> withDownloader(downloader: Downloader, block: () -> T): T {
        lock.lockInterruptibly()
        try {
            NewPipe.init(downloader)
            return block()
        } finally {
            lock.unlock()
        }
    }
}
