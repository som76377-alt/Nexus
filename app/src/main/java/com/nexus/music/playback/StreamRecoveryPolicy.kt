package com.nexus.music.playback

import androidx.media3.common.PlaybackException

/** Only known stream-address/range failures are eligible for provider re-resolution. */
internal object StreamRecoveryPolicy {
    fun classify(
        errorCode: Int,
        httpResponseCode: Int?,
        networkAvailable: Boolean
    ): StreamRecoveryReason? {
        if (httpResponseCode in RETRYABLE_HTTP_CODES) return StreamRecoveryReason.STALE_OR_REJECTED_URL
        if (errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
            return StreamRecoveryReason.READ_POSITION_OUT_OF_RANGE
        }
        if (!networkAvailable) return null
        return when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                StreamRecoveryReason.NETWORK
            else -> null
        }
    }

    private val RETRYABLE_HTTP_CODES = setOf(403, 404, 410, 416)
}

internal enum class StreamRecoveryReason {
    STALE_OR_REJECTED_URL,
    NETWORK,
    READ_POSITION_OUT_OF_RANGE
}
