package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCardDto
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Resolves only timestamps that come from ServusTV metadata.
 *
 * Priority is availability (`sunrise_timestamp`), then broadcast/program start (`start_time`), then
 * an explicitly visible date + time combination in editorial metadata. Returning null is
 * intentional: a local refresh/import timestamp is not a publication timestamp and must never be
 * substituted here.
 */
internal object ServusSourceTimestampPolicy {
    private val datePattern = Regex("""\b(\d{1,2})\.(\d{1,2})\.?\b""")
    private val timePattern = Regex("""\b(\d{1,2}):(\d{2})\s*(?:uhr)?\b""", RegexOption.IGNORE_CASE)

    fun resolve(card: ServusCardDto, nowMillis: Long): Long? {
        parseApiTimestamp(card.sunriseTimestamp)?.let { return it }
        parseApiTimestamp(card.startTime)?.let { return it }

        val source = listOfNotNull(
            card.title,
            card.subheading,
            card.shortDescription,
            card.longDescription,
        ).joinToString(" ")
        val date = parseLocalDate(source, nowMillis) ?: return null
        val time = parseLocalTime(source) ?: return null
        return date.atTime(time)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
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

    private fun parseLocalDate(source: String, nowMillis: Long): LocalDate? {
        val match = datePattern.find(source) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        var candidate = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
        if (candidate.isAfter(today.plusDays(31))) candidate = candidate.minusYears(1)
        return candidate
    }

    private fun parseLocalTime(source: String): LocalTime? {
        val match = timePattern.find(source) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }
}
