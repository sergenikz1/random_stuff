package org.scryertv.client.streaming

import androidx.media3.common.PlaybackException

/** A fixed budget per episode prevents automatic retries from looping on permanent errors. */
internal class PlaybackRecovery {
    private var attempts = 0
    fun reset() { attempts = 0 }

    fun nextDelayMs(code: Int? = null, httpStatus: Int? = null): Long? {
        if (attempts >= 2) return null
        val retryable = when (code) {
            null -> true // Resolving the content URL failed before a player was created.
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> true
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> httpStatus == null ||
                httpStatus in setOf(403,404,408,410,429) || httpStatus >= 500
            else -> false
        }
        return if (retryable) listOf(400L, 1200L)[attempts++] else null
    }
}
