package com.nexus.music.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
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
    fun rejectsUnknownOrDuplicateProviderConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { StreamProvider.parseOrder("newpipe,unknown") }
        assertThrows(IllegalArgumentException::class.java) { StreamProvider.parseOrder("piped,piped") }
        assertThrows(IllegalArgumentException::class.java) { StreamProvider.parseOrder(" , ") }
    }

    @Test
    fun classifiesKnownExtractorFailures() {
        assertEquals(StreamErrorClass.RATE_LIMITED,
            StreamErrorClassifier.classify(ReCaptchaException("limited", "https://youtube.com")))
        assertEquals(StreamErrorClass.ACCESS_BLOCKED,
            StreamErrorClassifier.classify(SignInConfirmNotBotException("verify")))
        assertEquals(StreamErrorClass.INVALID_SOURCE,
            StreamErrorClassifier.classify(IllegalArgumentException("bad URL")))
        assertEquals(StreamErrorClass.TRANSIENT,
            StreamErrorClassifier.classify(IOException("network unavailable")))
        assertEquals(StreamErrorClass.UNKNOWN,
            StreamErrorClassifier.classify(IllegalStateException("unexpected")))
    }
}
