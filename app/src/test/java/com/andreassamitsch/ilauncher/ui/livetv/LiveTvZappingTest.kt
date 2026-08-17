package com.andreassamitsch.ilauncher.ui.livetv

import androidx.media3.common.PlaybackException
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvZappingTest {
    @Test
    fun `moves through receiver order`() {
        assertEquals(3, LiveTvZapping.nextIndex(currentIndex = 2, size = 6, delta = 1))
        assertEquals(1, LiveTvZapping.nextIndex(currentIndex = 2, size = 6, delta = -1))
    }

    @Test
    fun `wraps at bouquet boundaries`() {
        assertEquals(0, LiveTvZapping.nextIndex(currentIndex = 5, size = 6, delta = 1))
        assertEquals(5, LiveTvZapping.nextIndex(currentIndex = 0, size = 6, delta = -1))
    }

    @Test
    fun `handles empty and invalid current index safely`() {
        assertEquals(0, LiveTvZapping.nextIndex(currentIndex = 4, size = 0, delta = 1))
        assertEquals(1, LiveTvZapping.nextIndex(currentIndex = 99, size = 3, delta = -1))
    }

    @Test
    fun `keeps selected service reference across refreshed channel metadata`() {
        val originalReferences = listOf("1:0:1:AAA", "1:0:1:BBB", "1:0:1:CCC")
        val refreshedReferences = originalReferences.toList()

        assertEquals(
            1,
            LiveTvZapping.indexForServiceReference(
                serviceReferences = originalReferences,
                currentServiceReference = "1:0:1:BBB",
            ),
        )
        assertEquals(
            1,
            LiveTvZapping.indexForServiceReference(
                serviceReferences = refreshedReferences,
                currentServiceReference = "1:0:1:BBB",
            ),
        )
    }

    @Test
    fun `falls back safely if selected service disappears`() {
        assertEquals(
            0,
            LiveTvZapping.indexForServiceReference(
                serviceReferences = listOf("first", "second"),
                currentServiceReference = "removed",
            ),
        )
    }

    @Test
    fun `formats next programme start in TV local time`() {
        val start = Instant.parse("2026-08-17T20:14:00Z").toEpochMilli()

        assertEquals(
            "22:14",
            formatLiveTvStartTime(start, ZoneId.of("Europe/Vienna")),
        )
    }

    @Test
    fun `retries transient MPEG TS parser failures`() {
        assertTrue(
            LiveTvPlaybackRecovery.shouldRetry(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                completedRetries = 0,
            ),
        )
        assertTrue(
            LiveTvPlaybackRecovery.shouldRetry(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                completedRetries = 1,
            ),
        )
    }

    @Test
    fun `stops automatic recovery after bounded retry count`() {
        assertFalse(
            LiveTvPlaybackRecovery.shouldRetry(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                completedRetries = LiveTvPlaybackRecovery.MAX_AUTO_RETRIES,
            ),
        )
    }

    @Test
    fun `does not hide unrelated permanent playback failures behind parser recovery`() {
        assertFalse(
            LiveTvPlaybackRecovery.shouldRetry(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                completedRetries = 0,
            ),
        )
    }

    @Test
    fun `backs off the second full stream restart`() {
        assertEquals(350L, LiveTvPlaybackRecovery.retryDelayMillis(1))
        assertEquals(900L, LiveTvPlaybackRecovery.retryDelayMillis(2))
    }
}
