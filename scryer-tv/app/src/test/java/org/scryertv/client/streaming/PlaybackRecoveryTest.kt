package org.scryertv.client.streaming

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun transientFailuresRetryWithFreshUrlsButHaveAFixedBudget() {
        val recovery = PlaybackRecovery()
        assertEquals(400L, recovery.nextDelayMs(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(1200L, recovery.nextDelayMs(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403))
        assertNull(recovery.nextDelayMs(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        recovery.reset()
        assertEquals(400L, recovery.nextDelayMs())
    }
    @Test fun unsupportedFormatsAndAccessRestrictionsDoNotLoop() {
        assertNull(PlaybackRecovery().nextDelayMs(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertNull(PlaybackRecovery().nextDelayMs(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 401))
    }
    @Test fun decoderInitializationCanTryAFallbackPlayer() {
        assertEquals(400L, PlaybackRecovery().nextDelayMs(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
    }
}
