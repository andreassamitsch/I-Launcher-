package com.andreassamitsch.ilauncher.data.tmdb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbNegativeMappingPolicyTest {
    @Test
    fun `mapping from older resolver policy is retried immediately`() {
        val now = TmdbRepository.RESOLVER_POLICY_CUTOFF_UTC_MILLIS + 1_000L
        assertTrue(
            TmdbRepository.shouldRetryNegativeMapping(
                TmdbRepository.RESOLVER_POLICY_CUTOFF_UTC_MILLIS - 1L,
                now,
            ),
        )
    }

    @Test
    fun `fresh negative mapping keeps short retry cache`() {
        val updated = TmdbRepository.RESOLVER_POLICY_CUTOFF_UTC_MILLIS + 1_000L
        assertFalse(
            TmdbRepository.shouldRetryNegativeMapping(
                updated,
                updated + TmdbRepository.NEGATIVE_MAPPING_RETRY_MILLIS - 1L,
            ),
        )
        assertTrue(
            TmdbRepository.shouldRetryNegativeMapping(
                updated,
                updated + TmdbRepository.NEGATIVE_MAPPING_RETRY_MILLIS,
            ),
        )
    }
}
