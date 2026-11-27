package com.nexus.music.catalog

import com.nexus.music.performance.NexusPerformance
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded, health-aware hedged failover shared by catalogue search and stream resolution.
 * The preferred provider starts immediately; slow requests are hedged at a fixed stagger.
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
    private data class DeferredFailure(val provider: StreamProvider, val error: Exception)

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
            "Provider order contains duplicates."
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
        operationName = "provider_search",
        operation = { it.search(query, scope) },
        isEmptySearchResult = { it.isEmpty() }
    )

    suspend fun resolveAudio(
        videoUrl: String,
        excludingProviders: Set<StreamProvider> = emptySet()
    ): ResolvedAudio = execute(
        healthOperation = ProviderOperation.RESOLVE,
        operationName = "provider_resolve",
        operation = { it.resolveAudio(videoUrl) },
        validateResult = validateResolvedAudio,
        excludingProviders = excludingProviders,
        mapResult = { provider, audio -> audio.copy(provider = provider) }
    )

    private suspend fun <T> execute(
        healthOperation: ProviderOperation,
        operationName: String,
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
        val tracing = NexusPerformance.isEnabled()
        val inheritedTrace = if (tracing) {
            currentCoroutineContext()[NexusPerformance.TraceContext]?.trace
        } else null
        val ownsTrace = inheritedTrace == null
        val trace = if (!tracing) null else inheritedTrace ?: NexusPerformance.begin(
            operationName,
            foreground = NexusPerformance.isForeground(),
            detail = "attempts=" + ordered.size + " providers=" +
                ordered.joinToString(",") { it.first.key }
        )
        if (trace != null) {
            NexusPerformance.event(trace, "provider_chain_begin",
                "attempts=" + ordered.size + " providers=" +
                    ordered.joinToString(",") { it.first.key })
        }
        val firstNonEmptySearchObserved = AtomicBoolean(false)

        return coroutineScope {
            val outcomes = Channel<Result<T>>(capacity = ordered.size)
            val relativeFailures = ConcurrentLinkedQueue<DeferredFailure>()
            val jobs = mutableListOf<kotlinx.coroutines.Job>()
            var nextIndex = 0
            var activeCount = 0

            fun launchNextProvider() {
                val attemptIndex = nextIndex++
                val (provider, adapter) = ordered[attemptIndex]
                activeCount++
                jobs += launch {
                    if (trace != null) NexusPerformance.event(
                        trace, "provider_attempt_start",
                        "provider=" + provider.key + " attempt=" + (attemptIndex + 1)
                    )
                    try {
                        val result = if (trace != null) {
                            withContext(NexusPerformance.TraceContext(trace)) {
                                mapResult(provider, validateResult(operation(adapter)))
                            }
                        } else {
                            mapResult(provider, validateResult(operation(adapter)))
                        }
                        val resultCount = (result as? Collection<*>)?.size
                        if (operationName == "provider_search" && resultCount != null &&
                            resultCount > 0 && firstNonEmptySearchObserved.compareAndSet(false, true)
                        ) {
                            if (trace != null) NexusPerformance.event(
                                trace, "first_nonempty_provider_result",
                                "provider=" + provider.key + " count=" + resultCount
                            )
                        }
                        healthStore.recordSuccess(provider, nowEpochMs(), healthOperation)
                        if (trace != null) NexusPerformance.event(
                            trace, "provider_attempt_success",
                            "provider=" + provider.key + " result_count=" + (resultCount?.toString() ?: "n/a")
                        )
                        outcomes.send(Result.success(result))
                    } catch (error: CancellationException) {
                        if (trace != null) NexusPerformance.event(
                            trace, "provider_attempt_cancelled", "provider=" + provider.key
                        )
                        throw error
                    } catch (error: Exception) {
                        if (trace != null) NexusPerformance.event(
                            trace, "provider_attempt_failure",
                            "provider=" + provider.key + " error=" + error.javaClass.simpleName +
                                " class=" + StreamErrorClassifier.classify(error)
                        )
                        when {
                            isSelfEvidentProviderFailure(error, healthOperation) -> recordFailure(provider, healthOperation, error)
                            isAmbiguousProviderFailure(error, healthOperation) -> relativeFailures.add(DeferredFailure(provider, error))
                        }
                        outcomes.send(Result.failure(error))
                    }
                }
            }

            fun recordCorroboratedFailures() {
                relativeFailures.forEach { failure -> recordFailure(failure.provider, healthOperation, failure.error, true) }
            }
            var lastError: Exception? = null
            var emptyResult: T? = null
            var sawEmptyResult = false
            try {
                launchNextProvider()
                while (activeCount > 0 || nextIndex < ordered.size) {
                    if (activeCount == 0) {
                        launchNextProvider()
                        continue
                    }

                    val outcome = if (nextIndex < ordered.size) {
                        // select commits exactly one ready clause, so a boundary result cannot be
                        // consumed by a cancelled receive while activeCount remains inflated.
                        select<Result<T>?> {
                            outcomes.onReceive { it }
                            onTimeout(hedgeDelayMs) { null }
                        }
                    } else {
                        outcomes.receive()
                    }

                    if (outcome == null) {
                        launchNextProvider()
                        continue
                    }

                    activeCount--
                    if (outcome.isSuccess) {
                        val result = outcome.getOrThrow()
                        if (isEmptySearchResult(result)) {
                            emptyResult = result
                            sawEmptyResult = true
                            if (nextIndex < ordered.size) launchNextProvider()
                            continue
                        }
                        if (trace != null) {
                            if (ownsTrace) NexusPerformance.finish(trace, "result=success")
                            else NexusPerformance.event(trace, "provider_chain_complete", "result=success")
                        }
                        recordCorroboratedFailures()
                        jobs.forEach { it.cancel() }
                        return@coroutineScope result
                    }

                    val error = outcome.exceptionOrNull() as Exception
                    if (StreamErrorClassifier.classify(error) == StreamErrorClass.INVALID_SOURCE) {
                        if (trace != null) {
                            if (ownsTrace) NexusPerformance.finish(trace, "invalid_source")
                            else NexusPerformance.event(trace, "provider_chain_complete", "result=invalid_source")
                        }
                        jobs.forEach { it.cancel() }
                        throw error
                    }
                    lastError = error
                    if (nextIndex < ordered.size) launchNextProvider()
                }

                if (sawEmptyResult) {
                    recordCorroboratedFailures()
                    if (trace != null) {
                        if (ownsTrace) NexusPerformance.finish(trace, "result=empty")
                        else NexusPerformance.event(trace, "provider_chain_complete", "result=empty")
                    }
                    @Suppress("UNCHECKED_CAST")
                    return@coroutineScope emptyResult as T
                }
                if (trace != null) {
                    if (ownsTrace) NexusPerformance.finish(trace, "result=failed")
                    else NexusPerformance.event(trace, "provider_chain_complete", "result=failed")
                }
                throw lastError ?: IOException("No configured stream provider could be attempted.")
            } catch (cancelled: CancellationException) {
                if (trace != null) {
                    if (ownsTrace) NexusPerformance.finish(trace, "cancelled")
                    else NexusPerformance.event(trace, "provider_chain_cancelled")
                }
                throw cancelled
            } finally {
                outcomes.close()
            }
        }
    }

    private fun isSelfEvidentProviderFailure(error: Exception, operation: ProviderOperation): Boolean = when (error) {
        is ProviderHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode in 500..599 || (operation == ProviderOperation.SEARCH && error.statusCode in 400..499)
        is ProviderStreamProbeException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode in 500..599
        is ProviderResponseException, is ReCaptchaException, is SignInConfirmNotBotException -> true
        else -> false
    }
    private fun isAmbiguousProviderFailure(error: Exception, operation: ProviderOperation): Boolean = when (error) {
        is ProviderHttpException -> operation == ProviderOperation.RESOLVE && error.statusCode in 401..403
        is ProviderStreamProbeException -> error.statusCode == 403 || error.statusCode == 410
        is ProviderResponseException, is ProviderContentException, is ProviderCapabilityException -> false
        else -> StreamErrorClassifier.classify(error) == StreamErrorClass.NETWORK
    }
    private fun recordFailure(provider: StreamProvider, operation: ProviderOperation, error: Exception, relativeEvidence: Boolean = false) {
        val errorClass = StreamErrorClassifier.classify(error)
        val failedAt = nowEpochMs()
        val failures = healthStore.get(provider, operation).consecutiveFailures + 1
        val cooldown = cooldownFor(errorClass, failures, error, relativeEvidence)
        val until = if (cooldown > 0L && failedAt > Long.MAX_VALUE - cooldown) Long.MAX_VALUE else failedAt + cooldown
        healthStore.recordFailure(provider, errorClass, failedAt, until, operation)
    }
    private fun cooldownFor(errorClass: StreamErrorClass, failures: Int, error: Exception, relativeEvidence: Boolean): Long {
        val multiplier = 1L shl (failures - 1).coerceIn(0, 5)
        if (relativeEvidence) return (RELATIVE_COOLDOWN_MS * multiplier).coerceAtMost(MAX_COOLDOWN_MS)
        return when (errorClass) {
            StreamErrorClass.TRANSIENT -> TRANSIENT_COOLDOWN_MS * multiplier
            StreamErrorClass.RATE_LIMITED -> (error as? ProviderHttpException)?.retryAfterMs ?: RATE_LIMIT_COOLDOWN_MS
            StreamErrorClass.ACCESS_BLOCKED -> ACCESS_BLOCK_COOLDOWN_MS
            StreamErrorClass.UNSUPPORTED -> UNSUPPORTED_COOLDOWN_MS
            StreamErrorClass.UNKNOWN -> UNKNOWN_COOLDOWN_MS * multiplier
            StreamErrorClass.INVALID_SOURCE, StreamErrorClass.CONTENT_UNAVAILABLE, StreamErrorClass.NETWORK -> 0L
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
