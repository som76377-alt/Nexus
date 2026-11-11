package com.nexus.music.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Bounded, health-aware hedged failover shared by catalogue search and stream resolution.
 * The preferred provider starts immediately; additional providers start at a fixed stagger.
 * The first successful result wins and remaining requests are cancelled.
 */
class ProviderResolverChain(
    adapters: List<StreamProviderAdapter>,
    private val healthStore: ProviderHealthStore,
    providerOrder: List<StreamProvider> = StreamProvider.DEFAULT_ORDER,
    private val maxAttempts: Int = providerOrder.size,
    private val hedgeDelayMs: Long = DEFAULT_HEDGE_DELAY_MS,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val adaptersByProvider = adapters.associateBy { it.provider }
    private val providerOrder = providerOrder.toList()

    init {
        require(adapters.isNotEmpty()) { "At least one stream provider adapter is required." }
        require(adaptersByProvider.size == adapters.size) { "Provider adapters must be unique." }
        require(this.providerOrder.isNotEmpty()) { "At least one stream provider must be configured." }
        require(this.providerOrder.distinct().size == this.providerOrder.size) {
            "Stream provider order contains duplicates."
        }
        require(maxAttempts in 1..this.providerOrder.size) {
            "Maximum provider attempts must be within the configured provider count."
        }
        require(hedgeDelayMs >= 0L) { "Hedge delay cannot be negative." }
    }

    suspend fun search(
        query: String,
        scope: YoutubeSearchScope = YoutubeSearchScope.EVERYTHING
    ): List<CatalogTrack> = execute { it.search(query, scope) }

    suspend fun resolveAudio(videoUrl: String): ResolvedAudio =
        execute { it.resolveAudio(videoUrl) }

    private suspend fun <T> execute(operation: suspend (StreamProviderAdapter) -> T): T {
        val configured = providerOrder.mapNotNull { provider ->
            adaptersByProvider[provider]?.let { provider to it }
        }
        require(configured.isNotEmpty()) { "No adapters match the configured provider order." }

        val now = nowEpochMs()
        val ordered = configured.sortedBy { (provider, _) ->
            if (healthStore.get(provider).cooldownUntilEpochMs > now) 1 else 0
        }.take(maxAttempts)

        return coroutineScope {
            val outcomes = Channel<Result<T>>(capacity = ordered.size)
            val jobs = ordered.mapIndexed { index, (provider, adapter) ->
                async {
                    if (index > 0) delay(hedgeDelayMs * index)
                    try {
                        val result = operation(adapter)
                        healthStore.recordSuccess(provider, nowEpochMs())
                        outcomes.send(Result.success(result))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (StreamErrorClassifier.classify(error) != StreamErrorClass.INVALID_SOURCE) {
                            recordFailure(provider, error)
                        }
                        outcomes.send(Result.failure(error))
                    }
                }
            }

            var lastError: Exception? = null
            try {
                repeat(ordered.size) {
                    val outcome = outcomes.receive()
                    if (outcome.isSuccess) {
                        jobs.forEach { it.cancel() }
                        return@coroutineScope outcome.getOrThrow()
                    }

                    val error = outcome.exceptionOrNull() as Exception
                    if (StreamErrorClassifier.classify(error) == StreamErrorClass.INVALID_SOURCE) {
                        jobs.forEach { it.cancel() }
                        throw error
                    }
                    lastError = error
                }
                throw lastError ?: IOException("No configured stream provider could be attempted.")
            } finally {
                outcomes.close()
            }
        }
    }

    private fun recordFailure(provider: StreamProvider, error: Exception) {
        val errorClass = StreamErrorClassifier.classify(error)
        val failedAt = nowEpochMs()
        val failures = healthStore.get(provider).consecutiveFailures + 1
        healthStore.recordFailure(
            provider = provider,
            errorClass = errorClass,
            atEpochMs = failedAt,
            cooldownUntilEpochMs = failedAt + cooldownFor(errorClass, failures, error)
        )
    }

    private fun cooldownFor(errorClass: StreamErrorClass, failures: Int, error: Exception): Long {
        val multiplier = 1L shl (failures - 1).coerceIn(0, 5)
        return when (errorClass) {
            StreamErrorClass.TRANSIENT -> TRANSIENT_COOLDOWN_MS * multiplier
            StreamErrorClass.RATE_LIMITED ->
                (error as? ProviderHttpException)?.retryAfterMs ?: RATE_LIMIT_COOLDOWN_MS
            StreamErrorClass.ACCESS_BLOCKED -> ACCESS_BLOCK_COOLDOWN_MS
            StreamErrorClass.UNSUPPORTED -> UNSUPPORTED_COOLDOWN_MS
            StreamErrorClass.UNKNOWN -> UNKNOWN_COOLDOWN_MS * multiplier
            StreamErrorClass.INVALID_SOURCE -> 0L
        }.coerceAtMost(MAX_COOLDOWN_MS)
    }

    private companion object {
        const val DEFAULT_HEDGE_DELAY_MS = 350L
        const val TRANSIENT_COOLDOWN_MS = 30_000L
        const val RATE_LIMIT_COOLDOWN_MS = 5 * 60_000L
        const val ACCESS_BLOCK_COOLDOWN_MS = 15 * 60_000L
        const val UNSUPPORTED_COOLDOWN_MS = 60 * 60_000L
        const val UNKNOWN_COOLDOWN_MS = 60_000L
        const val MAX_COOLDOWN_MS = 60 * 60_000L
    }
}
