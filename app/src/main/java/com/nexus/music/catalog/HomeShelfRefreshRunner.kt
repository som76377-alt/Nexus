package com.nexus.music.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * Runs shelves serially. The fetcher applies the supplied deadline after it acquires its
 * provider/extractor lease, so queue wait and foreground preemption do not consume shelf time.
 */
internal class HomeShelfRefreshRunner(private val perShelfTimeoutMs: Long) {
    init { require(perShelfTimeoutMs > 0L) }

    suspend fun run(
        shelves: List<HomeShelfSpec>,
        fetch: suspend (HomeShelfSpec, Long) -> List<CatalogTrack>,
        onResult: suspend (HomeShelfSpec, List<CatalogTrack>) -> Unit,
        onFailure: suspend (HomeShelfSpec, Throwable) -> Unit = { _, _ -> }
    ) = supervisorScope {
        for (shelf in shelves) {
            val shelfJob = launch {
                try {
                    onResult(shelf, fetch(shelf, perShelfTimeoutMs))
                } catch (timeout: TimeoutCancellationException) {
                    onFailure(shelf, timeout)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onFailure(shelf, error)
                }
            }
            shelfJob.join()
        }
    }
}
