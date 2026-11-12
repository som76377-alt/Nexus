package com.nexus.music.catalog

import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import java.io.IOException

class StreamProviderTest {
    @Test
    fun defaultOrderIsNewPipeThenPipedThenInvidiousAndCanBeConfigured() {
        assertEquals(
            listOf(StreamProvider.NEWPIPE, StreamProvider.PIPED, StreamProvider.INVIDIOUS),
            StreamProvider.DEFAULT_ORDER
        )
        assertEquals(
            listOf(StreamProvider.INVIDIOUS, StreamProvider.NEWPIPE),
            StreamProvider.parseOrder("invidious, NEWPIPE")
        )
    }

    @Test
    fun missingOrInvalidProviderConfigurationFallsBackToDefaultOrder() {
        assertEquals(StreamProvider.DEFAULT_ORDER, StreamProvider.parseOrder(""))
        assertEquals(StreamProvider.DEFAULT_ORDER, StreamProvider.parseOrder("newpipe,unknown"))
        assertEquals(StreamProvider.DEFAULT_ORDER, StreamProvider.parseOrder("piped,piped"))
        assertEquals(StreamProvider.DEFAULT_ORDER, StreamProvider.parseOrder(" , "))
    }

    @Test
    fun classifiesKnownExtractorFailures() {
        assertEquals(StreamErrorClass.RATE_LIMITED,
            StreamErrorClassifier.classify(ReCaptchaException("limited", "https://youtube.com")))
        assertEquals(StreamErrorClass.ACCESS_BLOCKED,
            StreamErrorClassifier.classify(SignInConfirmNotBotException("verify")))
        assertEquals(StreamErrorClass.INVALID_SOURCE,
            StreamErrorClassifier.classify(IllegalArgumentException("bad URL")))
        assertEquals(StreamErrorClass.NETWORK,
            StreamErrorClassifier.classify(IOException("network unavailable")))
        assertEquals(StreamErrorClass.CONTENT_UNAVAILABLE,
            StreamErrorClassifier.classify(ContentNotAvailableException("video unavailable")))
        assertEquals(StreamErrorClass.RATE_LIMITED,
            StreamErrorClassifier.classify(ProviderHttpException(429, "piped")))
        assertEquals(StreamErrorClass.ACCESS_BLOCKED,
            StreamErrorClassifier.classify(ProviderHttpException(403, "invidious")))
        assertEquals(StreamErrorClass.UNSUPPORTED,
            StreamErrorClassifier.classify(ProviderHttpException(404, "piped")))
        assertEquals(StreamErrorClass.TRANSIENT,
            StreamErrorClassifier.classify(ProviderHttpException(503, "piped")))
        assertEquals(StreamErrorClass.UNKNOWN,
            StreamErrorClassifier.classify(IllegalStateException("unexpected")))
    }
}
