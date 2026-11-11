package com.nexus.music.catalog

/**
 * Small circuit breaker for the on-device extractor. After a primary failure, route through
 * the server-side fallback for a short cooldown, then probe the primary again automatically.
 */
internal class ExtractorFailoverPolicy(
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var retryPrimaryAt = 0L

    @Synchronized
    fun shouldTryPrimary(): Boolean = clock() >= retryPrimaryAt

    @Synchronized
    fun primarySucceeded() {
        retryPrimaryAt = 0L
    }

    @Synchronized
    fun primaryFailed() {
        retryPrimaryAt = clock() + cooldownMillis
    }

    companion object {
        private const val DEFAULT_COOLDOWN_MS = 2 * 60 * 1000L
    }
}
