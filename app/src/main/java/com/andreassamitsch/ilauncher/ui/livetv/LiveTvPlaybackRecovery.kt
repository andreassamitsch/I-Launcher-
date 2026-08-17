package com.andreassamitsch.ilauncher.ui.livetv

import androidx.media3.common.PlaybackException

/**
 * Bounded recovery policy for transient Live-TV parser failures.
 *
 * Media3 deliberately treats ParserException as fatal in its default load-error policy. On the
 * Gigablue target device rapid zapping can transiently produce an unusable initial MPEG-TS read,
 * while reconnecting to the same service immediately succeeds. Retry only those parser failures
 * and keep the number of full stream restarts small so a genuinely unsupported stream still
 * surfaces as an error.
 */
internal object LiveTvPlaybackRecovery {
    const val MAX_AUTO_RETRIES = 2

    fun shouldRetry(errorCode: Int, completedRetries: Int): Boolean {
        if (completedRetries >= MAX_AUTO_RETRIES) return false
        return errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
    }

    fun retryDelayMillis(retryAttempt: Int): Long = when (retryAttempt) {
        1 -> 350L
        else -> 900L
    }
}
