package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCardDto
import com.andreassamitsch.servusprovider.api.ServusNetwork
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale

object ServusNewsPolicy {
    private const val MIN_FULL_EDITION_MILLIS = 5L * 60L * 1000L
    private val dateInTitle = Regex("""\b(\d{1,2})\.(\d{1,2})\.?\b""")
    private val excludedFragments = listOf(
        "90 sekunden",
        "90-sekunden",
        "kurzmeldung",
        "newsflash",
        "news flash",
    )

    fun couldBelongToNews(card: ServusCardDto): Boolean {
        val text = searchableText(card)
        return card.id?.isNotBlank() == true &&
            (text.contains("servus nachrichten") || text.contains("nachrichten 19:20"))
    }

    fun toFullNewsEpisode(card: ServusCardDto, nowMillis: Long = System.currentTimeMillis()): ServusNewsEpisode? {
        val id = card.id?.takeIf { it.isNotBlank() } ?: return null
        val title = card.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val searchText = searchableText(card)
        val duration = card.duration ?: return null

        if (!searchText.contains("nachrichten")) return null
        if (!searchText.contains("19:20")) return null
        if (excludedFragments.any(searchText::contains)) return null
        if (duration < MIN_FULL_EDITION_MILLIS) return null
        if (card.playable == false) return null

        return ServusNewsEpisode(
            id = id,
            title = title,
            showName = card.showName?.takeIf { it.isNotBlank() } ?: card.subheading?.takeIf { it.isNotBlank() },
            description = card.longDescription?.takeIf { it.length > 20 }
                ?: card.shortDescription?.takeIf { it.isNotBlank() },
            durationMillis = duration,
            publishedAtMillis = parsePublishedAt(card, nowMillis),
            artworkUri = landscapeArtwork(id, card.mediaResources),
        )
    }

    /**
     * Servus kann dieselbe 19:20-Ausgabe über mehrere Such-/Collection-Pfade mit unterschiedlichen
     * Content-IDs liefern. Für unseren Kanal ist die fachliche Identität deshalb die Ausgabe eines
     * Kalendertags, nicht die API-ID.
     */
    fun editionKey(episode: ServusNewsEpisode): String {
        titleEditionDate(episode.title)?.let { return "19:20-$it" }
        val localDate = Instant.ofEpochMilli(episode.publishedAtMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return "19:20-$localDate"
    }

    fun deduplicateEditions(episodes: List<ServusNewsEpisode>): List<ServusNewsEpisode> {
        return episodes
            .groupBy(::editionKey)
            .values
            .mapNotNull { candidates ->
                candidates.maxWithOrNull(
                    compareBy<ServusNewsEpisode> { it.publishedAtMillis }
                        .thenBy { it.durationMillis },
                )
            }
            .sortedByDescending { it.publishedAtMillis }
    }

    fun landscapeArtwork(id: String, resources: List<String>): String? {
        val resource = resources.firstOrNull { name ->
            name.contains("landscape", ignoreCase = true) &&
                !name.contains("cover_", ignoreCase = true) &&
                !name.contains("treatment_", ignoreCase = true)
        } ?: return null
        return "${ServusNetwork.ARTWORK_BASE_URL}$id/$resource/f_avif,c_fill,w_1280,q_70?namespace=stv&refresh=true"
    }

    private fun titleEditionDate(title: String): String? {
        val match = dateInTitle.find(title) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        return "%02d-%02d".format(Locale.ROOT, month, day)
    }

    private fun searchableText(card: ServusCardDto): String = listOfNotNull(
        card.title,
        card.showName,
        card.subheading,
    ).joinToString(" ")
        .lowercase(Locale.GERMAN)
        .replace('–', '-')

    private fun parsePublishedAt(card: ServusCardDto, nowMillis: Long): Long {
        card.sunriseTimestamp?.let { value ->
            try {
                return Instant.parse(value).toEpochMilli()
            } catch (_: DateTimeParseException) {
                // Some API responses omit a timezone. The title date below is the deterministic fallback.
            }
        }

        val match = dateInTitle.find(card.title.orEmpty()) ?: return nowMillis
        val day = match.groupValues[1].toIntOrNull() ?: return nowMillis
        val month = match.groupValues[2].toIntOrNull() ?: return nowMillis
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        var candidate = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return nowMillis
        if (candidate.isAfter(today.plusDays(31))) {
            candidate = candidate.minusYears(1)
        }
        return candidate.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
