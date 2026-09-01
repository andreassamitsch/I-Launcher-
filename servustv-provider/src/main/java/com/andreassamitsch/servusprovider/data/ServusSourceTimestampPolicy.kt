package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCardDto
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Resolves only a ServusTV-provided VOD availability timestamp.
 *
 * `start_time`/`end_time` belong to the linear programme guide, and a visible time such as 19:20 in
 * an episode title describes the broadcast slot. Neither is a publication timestamp, so neither is
 * allowed as a fallback here. If `sunrise_timestamp` is absent, returning null is intentional.
 */
internal object ServusSourceTimestampPolicy {
    fun resolve(card: ServusCardDto, nowMillis: Long): Long? {
        // Keep nowMillis in the signature for callers/tests that already pass a refresh reference
        // time. It must never be returned or otherwise used as a publication fallback.
        @Suppress("UNUSED_VARIABLE")
        val ignoredRefreshTime = nowMillis
        return parseApiTimestamp(card.sunriseTimestamp)
    }

    private fun parseApiTimestamp(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        try {
            return Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            // Continue with the other ISO forms used by the API.
        }
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            // Some API responses omit an offset entirely.
        }
        return runCatching {
            LocalDateTime.parse(value.take(19))
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }
}
