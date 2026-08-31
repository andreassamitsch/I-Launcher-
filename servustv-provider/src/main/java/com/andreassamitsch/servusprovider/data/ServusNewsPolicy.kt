package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCardDto
import com.andreassamitsch.servusprovider.api.ServusNetwork
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale

/** Supported ServusTV formats that are intentionally exposed by this small standalone app. */
enum class ServusContentKind {
    FULL_NEWS,
    NEWS_90_SECONDS,
    WEGSCHEIDER,
}

object ServusNewsPolicy {
    private const val MIN_FULL_EDITION_MILLIS = 5L * 60L * 1000L
    private const val MIN_90_SECONDS_MILLIS = 45L * 1000L
    private const val MAX_90_SECONDS_MILLIS = 5L * 60L * 1000L
    private const val MIN_WEGSCHEIDER_MILLIS = 4L * 60L * 1000L

    private val datePattern = Regex("""\b(\d{1,2})\.(\d{1,2})\.?\b""")
    private val timePattern = Regex("""\b(\d{1,2}):(\d{2})\s*(?:uhr)?\b""", RegexOption.IGNORE_CASE)
    private val excludedFullNewsFragments = listOf(
        "90 sekunden",
        "90-sekunden",
        "kurzmeldung",
        "newsflash",
        "news flash",
    )

    /**
     * Discovery is intentionally broader than final acceptance. Search results can contain a page
     * card without duration/playability that links to the collections holding the actual episodes.
     */
    fun couldBelongToSupportedContent(card: ServusCardDto): Boolean {
        if (card.id.isNullOrBlank()) return false
        val text = searchableText(card)
        return contentKind(text) != null ||
            text.contains("servus nachrichten") ||
            text.contains("wegscheider")
    }

    /** Kept as a narrow helper for existing tests and callers that explicitly need the 19:20 format. */
    fun toFullNewsEpisode(
        card: ServusCardDto,
        nowMillis: Long = System.currentTimeMillis(),
    ): ServusNewsEpisode? {
        if (contentKind(searchableText(card)) != ServusContentKind.FULL_NEWS) return null
        return toSupportedEpisode(card, nowMillis)
    }

    fun toSupportedEpisode(
        card: ServusCardDto,
        nowMillis: Long = System.currentTimeMillis(),
    ): ServusNewsEpisode? {
        val id = card.id?.takeIf { it.isNotBlank() } ?: return null
        val title = card.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val text = searchableText(card)
        val kind = contentKind(text) ?: return null
        val duration = card.duration ?: return null

        if (card.playable == false) return null
        when (kind) {
            ServusContentKind.FULL_NEWS -> {
                if (!text.contains("19:20")) return null
                if (excludedFullNewsFragments.any(text::contains)) return null
                if (duration < MIN_FULL_EDITION_MILLIS) return null
            }

            ServusContentKind.NEWS_90_SECONDS -> {
                if (duration !in MIN_90_SECONDS_MILLIS..MAX_90_SECONDS_MILLIS) return null
            }

            ServusContentKind.WEGSCHEIDER -> {
                if (duration < MIN_WEGSCHEIDER_MILLIS) return null
            }
        }

        return ServusNewsEpisode(
            id = id,
            title = title,
            showName = canonicalShowName(kind, card),
            description = card.longDescription?.takeIf { it.length > 20 }
                ?: card.shortDescription?.takeIf { it.isNotBlank() },
            durationMillis = duration,
            publishedAtMillis = parsePublishedAt(card, nowMillis),
            artworkUri = landscapeArtwork(id, card.mediaResources),
        )
    }

    fun contentKind(episode: ServusNewsEpisode): ServusContentKind? = contentKind(
        listOfNotNull(episode.title, episode.showName)
            .joinToString(" ")
            .lowercase(Locale.GERMAN)
            .replace('–', '-'),
    )

    fun displayLabel(episode: ServusNewsEpisode): String = when (contentKind(episode)) {
        ServusContentKind.FULL_NEWS -> "Servus Nachrichten 19:20"
        ServusContentKind.NEWS_90_SECONDS -> "Servus Nachrichten in 90 Sekunden"
        ServusContentKind.WEGSCHEIDER -> "Der Wegscheider"
        null -> episode.showName?.takeIf { it.isNotBlank() } ?: "ServusTV"
    }

    /**
     * The API can expose the same item through search and collections with different content IDs.
     * We therefore deduplicate by editorial identity while still preserving multiple 90-second
     * updates on the same day.
     */
    fun contentKey(episode: ServusNewsEpisode): String {
        val instant = Instant.ofEpochMilli(episode.publishedAtMillis)
        val localDateTime = instant.atZone(ZoneId.systemDefault())
        val date = localDateTime.toLocalDate()
        val normalizedTitle = normalizeTitle(episode.title)
        return when (contentKind(episode)) {
            ServusContentKind.FULL_NEWS -> "full-news-$date"
            ServusContentKind.NEWS_90_SECONDS -> {
                val minute = localDateTime.withSecond(0).withNano(0).toLocalTime()
                "news-90-$date-$minute-$normalizedTitle"
            }
            ServusContentKind.WEGSCHEIDER -> "wegscheider-$date-$normalizedTitle"
            null -> "unknown-$date-$normalizedTitle"
        }
    }

    fun deduplicateEpisodes(episodes: List<ServusNewsEpisode>): List<ServusNewsEpisode> {
        return episodes
            .distinctBy { it.id }
            .groupBy(::contentKey)
            .values
            .mapNotNull { candidates ->
                candidates.maxWithOrNull(
                    compareBy<ServusNewsEpisode> { it.publishedAtMillis }
                        .thenBy { it.durationMillis },
                )
            }
            .sortedByDescending { it.publishedAtMillis }
    }

    /** Backwards-compatible name used by the earlier 19:20-only prototype. */
    fun deduplicateEditions(episodes: List<ServusNewsEpisode>): List<ServusNewsEpisode> =
        deduplicateEpisodes(episodes)

    /** Backwards-compatible key used by the earlier 19:20-only prototype tests. */
    fun editionKey(episode: ServusNewsEpisode): String = contentKey(episode)

    fun landscapeArtwork(id: String, resources: List<String>): String? {
        val resource = resources.firstOrNull { name ->
            name.contains("landscape", ignoreCase = true) &&
                !name.contains("cover_", ignoreCase = true) &&
                !name.contains("treatment_", ignoreCase = true)
        } ?: return null
        // ServusTV On itself currently requests WebP from this CDN. WebP is supported by Android
        // across our whole minSdk range, unlike AVIF on older Android versions.
        return "${ServusNetwork.ARTWORK_BASE_URL}$id/$resource/f_webp,c_fill,w_1280,q_70?namespace=stv&refresh=true"
    }

    private fun contentKind(text: String): ServusContentKind? = when {
        text.contains("servus nachrichten in 90 sekunden") ||
            text.contains("nachrichten in 90 sekunden") ||
            text.contains("90-sekunden") -> ServusContentKind.NEWS_90_SECONDS

        text.contains("der wegscheider") || text.contains("wegscheider") ->
            ServusContentKind.WEGSCHEIDER

        text.contains("nachrichten") && text.contains("19:20") -> ServusContentKind.FULL_NEWS
        else -> null
    }

    private fun canonicalShowName(kind: ServusContentKind, card: ServusCardDto): String {
        val supplied = card.showName?.trim()?.takeIf { it.isNotBlank() }
        return when (kind) {
            ServusContentKind.FULL_NEWS -> supplied ?: "Servus Nachrichten"
            ServusContentKind.NEWS_90_SECONDS -> "Servus Nachrichten in 90 Sekunden"
            ServusContentKind.WEGSCHEIDER -> "Der Wegscheider"
        }
    }

    private fun searchableText(card: ServusCardDto): String = listOfNotNull(
        card.title,
        card.showName,
        card.subheading,
        card.shortDescription,
    ).joinToString(" ")
        .lowercase(Locale.GERMAN)
        .replace('–', '-')

    private fun parsePublishedAt(card: ServusCardDto, nowMillis: Long): Long {
        card.sunriseTimestamp?.let { value ->
            try {
                return Instant.parse(value).toEpochMilli()
            } catch (_: DateTimeParseException) {
                // Some API responses omit a timezone. Fall through to visible date/time metadata.
            }
        }

        val source = listOfNotNull(
            card.title,
            card.subheading,
            card.shortDescription,
            card.longDescription,
        ).joinToString(" ")
        val date = parseLocalDate(source, nowMillis) ?: return nowMillis
        val time = parseLocalTime(source) ?: LocalTime.MIDNIGHT
        return date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun parseLocalDate(source: String, nowMillis: Long): LocalDate? {
        val match = datePattern.find(source) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
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

    private fun normalizeTitle(value: String): String = value
        .lowercase(Locale.GERMAN)
        .replace(Regex("""[^a-z0-9äöüß]+"""), "-")
        .trim('-')
        .take(96)
}
