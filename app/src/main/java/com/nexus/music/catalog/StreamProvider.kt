package com.nexus.music.catalog

import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import java.io.IOException

/** Stable provider identity; the configured order is deliberately independent of enum order. */
enum class StreamProvider(val key: String) {
    NEWPIPE("newpipe"),
    PIPED("piped"),
    INVIDIOUS("invidious");

    companion object {
        val DEFAULT_ORDER = listOf(NEWPIPE, PIPED, INVIDIOUS)

        fun parseOrder(value: String): List<StreamProvider> = runCatching {
            val parsed = value.split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map { key ->
                    entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
                        ?: throw IllegalArgumentException("Unknown stream provider: $key")
                }
            require(parsed.isNotEmpty()) { "At least one stream provider must be configured." }
            require(parsed.distinct().size == parsed.size) { "Stream provider order contains duplicates." }
            parsed
        }.getOrDefault(DEFAULT_ORDER)
    }
}

enum class StreamErrorClass {
    TRANSIENT, RATE_LIMITED, ACCESS_BLOCKED, INVALID_SOURCE, UNSUPPORTED, CONTENT_UNAVAILABLE, NETWORK, UNKNOWN
}

/** A provider cannot faithfully perform a requested operation or filter. */
class ProviderCapabilityException(message: String) : IOException(message)

/** A valid provider may not expose a playable audio format for one particular video. */
class ProviderContentException(message: String) : IOException(message)

/** HTTP response failure retaining status so provider health uses the correct cooldown class. */
class ProviderHttpException(
    val statusCode: Int,
    provider: String,
    val retryAfterMs: Long? = null
) : IOException("$provider API returned HTTP $statusCode.")

object StreamErrorClassifier {
    fun classify(error: Throwable): StreamErrorClass = when (error) {
        is ContentNotAvailableException -> StreamErrorClass.CONTENT_UNAVAILABLE
        is ReCaptchaException -> StreamErrorClass.RATE_LIMITED
        is ProviderCapabilityException -> StreamErrorClass.UNSUPPORTED
        is ProviderHttpException -> when (error.statusCode) {
            429 -> StreamErrorClass.RATE_LIMITED
            401, 403 -> StreamErrorClass.ACCESS_BLOCKED
            400, 404, 405, 410, 422, 501 -> StreamErrorClass.UNSUPPORTED
            else -> StreamErrorClass.TRANSIENT
        }
        is SignInConfirmNotBotException -> StreamErrorClass.ACCESS_BLOCKED
        is IllegalArgumentException -> StreamErrorClass.INVALID_SOURCE
        is IOException -> StreamErrorClass.NETWORK
        else -> StreamErrorClass.UNKNOWN
    }
}
