package com.nexus.music.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

/** Each shelf has its own deadline/failure boundary; only one background shelf is active. */
internal class HomeShelfRefreshRunner(private val perShelfTimeoutMs: Long) {
    init { require(perShelfTimeoutMs > 0L) }

    suspend fun run(
        shelves: List<HomeShelfSpec>,
        fetch: suspend (HomeShelfSpec) -> List<CatalogTrack>,
        onResult: suspend (HomeShelfSpec, List<CatalogTrack>) -> Unit,
        onFailure: suspend (HomeShelfSpec, Throwable) -> Unit = { _, _ -> }
    ) = supervisorScope {
        for (shelf in shelves) {
            val shelfJob = launch {
                try {
                    onResult(shelf, withTimeout(perShelfTimeoutMs) { fetch(shelf) })
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
