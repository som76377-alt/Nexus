package com.nexus.music.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamRecoveryPolicyTest {
    @Test
    fun onlySpecifiedHttpStatusesTriggerRecovery() {
        listOf(403, 404, 410, 416).forEach { code ->
            assertEquals(
                StreamRecoveryReason.STALE_OR_REJECTED_URL,
                StreamRecoveryPolicy.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, code, true)
            )
        }
        listOf(400, 401, 408, 429, 500, 503).forEach { code ->
            assertNull(StreamRecoveryPolicy.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, code, true))
        }
    }

    @Test
    fun networkFailureAndTimeoutRequireValidatedNetwork() {
        assertEquals(
            StreamRecoveryReason.NETWORK,
            StreamRecoveryPolicy.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null, true)
        )
        assertEquals(
            StreamRecoveryReason.NETWORK,
            StreamRecoveryPolicy.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null, true)
        )
        assertNull(StreamRecoveryPolicy.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null, false))
        assertNull(StreamRecoveryPolicy.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null, false))
    }

    @Test
    fun readPositionOutOfRangeIsRecoverableWithoutNetwork() {
        assertEquals(
            StreamRecoveryReason.READ_POSITION_OUT_OF_RANGE,
            StreamRecoveryPolicy.classify(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, null, false)
        )
    }

    @Test
    fun decoderAndParsingFailuresNeverTriggerRecovery() {
        listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
        ).forEach { code ->
            assertNull(StreamRecoveryPolicy.classify(code, null, true))
        }
    }
}
