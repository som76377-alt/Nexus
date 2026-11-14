package com.nexus.music.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

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
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val validateResolvedAudio: suspend (ResolvedAudio) -> ResolvedAudio = { it }
) {
    private data class DeferredFailure(
        val provider: StreamProvider,
        val error: Exception
    )

    private val adaptersByProvider = adapters.associateBy { it.provider }
    @Volatile
    private var providerOrder = providerOrder.toList()

    fun updateProviderOrder(order: List<StreamProvider>) {
        require(order.isNotEmpty()) { "At least one stream provider must be enabled." }
        require(order.distinct().size == order.size) { "Stream provider order contains duplicates." }
        require(order.all(adaptersByProvider::containsKey)) { "Provider order references an unavailable adapter." }
        providerOrder = order.toList()
    }

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
    ): List<CatalogTrack> = execute(
        healthOperation = ProviderOperation.SEARCH,
        operation = { it.search(query, scope) },
        isEmptySearchResult = { it.isEmpty() }
    )

    suspend fun resolveAudio(
        videoUrl: String,
        excludingProviders: Set<StreamProvider> = emptySet()
    ): ResolvedAudio = execute(
        healthOperation = ProviderOperation.RESOLVE,
        operation = { it.resolveAudio(videoUrl) },
        validateResult = validateResolvedAudio,
        excludingProviders = excludingProviders,
        mapResult = { provider, audio -> audio.copy(provider = provider) }
    )

    private suspend fun <T> execute(
        healthOperation: ProviderOperation,
        operation: suspend (StreamProviderAdapter) -> T,
        isEmptySearchResult: (T) -> Boolean = { false },
        validateResult: suspend (T) -> T = { it },
        excludingProviders: Set<StreamProvider> = emptySet(),
        mapResult: (StreamProvider, T) -> T = { _, result -> result }
    ): T {
        val configured = providerOrder.filterNot(excludingProviders::contains).mapNotNull { provider ->
            adaptersByProvider[provider]?.let { provider to it }
        }
        require(configured.isNotEmpty()) { "No adapters match the configured provider order." }

        val now = nowEpochMs()
        val ordered = configured.sortedBy { (provider, _) ->
            if (healthStore.get(provider, healthOperation).cooldownUntilEpochMs > now) 1 else 0
        }.take(maxAttempts)

        return coroutineScope {
            val outcomes = Channel<Result<T>>(capacity = ordered.size)
            val relativeFailures = ConcurrentLinkedQueue<DeferredFailure>()
            val jobs = ordered.mapIndexed { index, (provider, adapter) ->
                async {
                    if (index > 0) delay(hedgeDelayMs * index)
                    try {
                        val result = mapResult(provider, validateResult(operation(adapter)))
                        healthStore.recordSuccess(provider, nowEpochMs(), healthOperation)
                        outcomes.send(Result.success(result))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        when {
                            isSelfEvidentProviderFailure(error, healthOperation) ->
                                recordFailure(provider, healthOperation, error)
                            isAmbiguousProviderFailure(error, healthOperation) ->
                                relativeFailures.add(DeferredFailure(provider, error))
                        }
                        outcomes.send(Result.failure(error))
                    }
                }
            }

            fun recordCorroboratedFailures() {
                relativeFailures.forEach { failure ->
                    recordFailure(failure.provider, healthOperation, failure.error, relativeEvidence = true)
                }
            }

            var lastError: Exception? = null
            var emptyResult: T? = null
            var sawEmptyResult = false
            try {
                repeat(ordered.size) {
                    val outcome = outcomes.receive()
                    if (outcome.isSuccess) {
                        val result = outcome.getOrThrow()
                        if (isEmptySearchResult(result)) {
                            emptyResult = result
                            sawEmptyResult = true
                            return@repeat
                        }
                        jobs.forEach { it.cancel() }
                        recordCorroboratedFailures()
                        return@coroutineScope result
                    }

                    val error = outcome.exceptionOrNull() as Exception
                    if (StreamErrorClassifier.classify(error) == StreamErrorClass.INVALID_SOURCE) {
                        jobs.forEach { it.cancel() }
                        throw error
                    }
                    lastError = error
                }
                if (sawEmptyResult) {
                    recordCorroboratedFailures()
                    @Suppress("UNCHECKED_CAST")
                    return@coroutineScope emptyResult as T
                }
                throw lastError ?: IOException("No configured stream provider could be attempted.")
            } finally {
                outcomes.close()
            }
        }
    }

    private fun isSelfEvidentProviderFailure(
        error: Exception,
        operation: ProviderOperation
    ): Boolean = when (error) {
        is ProviderHttpException -> error.statusCode == 408 ||
            error.statusCode == 429 ||
            error.statusCode in 500..599 ||
            (operation == ProviderOperation.SEARCH && error.statusCode in 400..499)
        is ProviderStreamProbeException -> error.statusCode == 408 ||
            error.statusCode == 429 ||
            error.statusCode in 500..599
        is ProviderResponseException,
        is ReCaptchaException,
        is SignInConfirmNotBotException -> true
        else -> false
    }

    private fun isAmbiguousProviderFailure(
        error: Exception,
        operation: ProviderOperation
    ): Boolean = when (error) {
        is ProviderHttpException ->
            operation == ProviderOperation.RESOLVE && error.statusCode in 401..403
        is ProviderStreamProbeException -> error.statusCode == 403 || error.statusCode == 410
        is ProviderResponseException,
        is ProviderContentException,
        is ProviderCapabilityException -> false
        else -> StreamErrorClassifier.classify(error) == StreamErrorClass.NETWORK
    }

    private fun recordFailure(
        provider: StreamProvider,
        operation: ProviderOperation,
        error: Exception,
        relativeEvidence: Boolean = false
    ) {
        val errorClass = StreamErrorClassifier.classify(error)
        val failedAt = nowEpochMs()
        val failures = healthStore.get(provider, operation).consecutiveFailures + 1
        val cooldown = cooldownFor(errorClass, failures, error, relativeEvidence)
        val cooldownUntil = if (cooldown > 0L && failedAt > Long.MAX_VALUE - cooldown) {
            Long.MAX_VALUE
        } else {
            failedAt + cooldown
        }
        healthStore.recordFailure(
            provider = provider,
            errorClass = errorClass,
            atEpochMs = failedAt,
            cooldownUntilEpochMs = cooldownUntil,
            operation = operation
        )
    }

    private fun cooldownFor(
        errorClass: StreamErrorClass,
        failures: Int,
        error: Exception,
        relativeEvidence: Boolean
    ): Long {
        val multiplier = 1L shl (failures - 1).coerceIn(0, 5)
        if (relativeEvidence) return (RELATIVE_COOLDOWN_MS * multiplier).coerceAtMost(MAX_COOLDOWN_MS)
        return when (errorClass) {
            StreamErrorClass.TRANSIENT -> TRANSIENT_COOLDOWN_MS * multiplier
            StreamErrorClass.RATE_LIMITED ->
                (error as? ProviderHttpException)?.retryAfterMs ?: RATE_LIMIT_COOLDOWN_MS
            StreamErrorClass.ACCESS_BLOCKED -> ACCESS_BLOCK_COOLDOWN_MS
            StreamErrorClass.UNSUPPORTED -> UNSUPPORTED_COOLDOWN_MS
            StreamErrorClass.UNKNOWN -> UNKNOWN_COOLDOWN_MS * multiplier
            StreamErrorClass.INVALID_SOURCE,
            StreamErrorClass.CONTENT_UNAVAILABLE,
            StreamErrorClass.NETWORK -> 0L
        }.coerceAtMost(MAX_COOLDOWN_MS)
    }

    private companion object {
        const val DEFAULT_HEDGE_DELAY_MS = 350L
        const val RELATIVE_COOLDOWN_MS = 15_000L
        const val TRANSIENT_COOLDOWN_MS = 30_000L
        const val RATE_LIMIT_COOLDOWN_MS = 5 * 60_000L
        const val ACCESS_BLOCK_COOLDOWN_MS = 15 * 60_000L
        const val UNSUPPORTED_COOLDOWN_MS = 60 * 60_000L
        const val UNKNOWN_COOLDOWN_MS = 60_000L
        const val MAX_COOLDOWN_MS = 60 * 60_000L
    }
}
