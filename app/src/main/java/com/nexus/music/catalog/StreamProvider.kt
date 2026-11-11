package com.nexus.music.catalog

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

        fun parseOrder(value: String): List<StreamProvider> {
            val parsed = value.split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map { key ->
                    entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
                        ?: throw IllegalArgumentException("Unknown stream provider: $key")
                }
            require(parsed.isNotEmpty()) { "At least one stream provider must be configured." }
            require(parsed.distinct().size == parsed.size) { "Stream provider order contains duplicates." }
            return parsed
        }
    }
}

/** Classification controls fallback and health penalties; it never changes the original error. */
enum class StreamErrorClass {
    TRANSIENT,
    RATE_LIMITED,
    ACCESS_BLOCKED,
    INVALID_SOURCE,
    UNSUPPORTED,
    UNKNOWN
}

object StreamErrorClassifier {
    fun classify(error: Throwable): StreamErrorClass = when (error) {
        is ReCaptchaException -> StreamErrorClass.RATE_LIMITED
        is SignInConfirmNotBotException -> StreamErrorClass.ACCESS_BLOCKED
        is IllegalArgumentException -> StreamErrorClass.INVALID_SOURCE
        is IOException -> StreamErrorClass.TRANSIENT
        else -> StreamErrorClass.UNKNOWN
    }
}
