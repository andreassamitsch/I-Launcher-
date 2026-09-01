package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCardDto
import com.andreassamitsch.servusprovider.api.ServusNetwork
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Locale

object ServusCatalogPolicy {
    private val datePattern = Regex("""\b(\d{1,2})\.(\d{1,2})\.?\b""")
    private val timePattern = Regex("""\b(\d{1,2}):(\d{2})\s*(?:uhr)?\b""", RegexOption.IGNORE_CASE)
    private val nextOffsetPattern = Regex("""(?:[?&]offset=)(\d+)""")

    fun isShowCard(card: ServusCardDto): Boolean =
        card.id?.isNotBlank() == true &&
            card.title?.isNotBlank() == true &&
            card.type == "page" &&
            card.contentType != "film"

    /**
     * ServusTV can expose market-/rights-specific catalogue collections that are present on the
     * landing page but deliberately reject access. Only that explicit 403 is safe to ignore.
     * Network failures, server errors and unexpected response codes must still fail the catalogue
     * refresh so an incomplete snapshot does not overwrite the last good cache.
     */
    fun canSkipCategoryHttpCode(statusCode: Int): Boolean = statusCode == 403

    fun buildShow(
        categoryId: String,
        categoryTitle: String,
        card: ServusCardDto,
        detail: ServusCardDto?,
        episodes: List<ServusNewsEpisode>,
    ): ServusShow? {
        val id = card.id?.takeIf { it.isNotBlank() } ?: return null
        val title = detail?.title?.takeIf { !it.isNullOrBlank() }
            ?: card.title?.takeIf { it.isNotBlank() }
            ?: return null
        val resources = (detail?.mediaResources.orEmpty() + card.mediaResources).distinct()
        return ServusShow(
            id = id,
            title = title,
            description = detail?.longDescription?.takeIf { it.isNotBlank() }
                ?: detail?.shortDescription?.takeIf { it.isNotBlank() }
                ?: card.longDescription?.takeIf { it.isNotBlank() }
                ?: card.shortDescription?.takeIf { it.isNotBlank() },
            categoryId = categoryId,
            categoryTitle = categoryTitle,
            artworkUri = landscapeArtwork(id, resources),
            squareArtworkUri = squareArtwork(id, resources),
            logoUri = titleTreatment(id, resources),
            episodes = episodes,
        )
    }

    fun toShowEpisode(
        card: ServusCardDto,
        showId: String,
        showTitle: String,
        categoryId: String,
        categoryTitle: String,
        showLogoUri: String?,
        nowMillis: Long,
    ): ServusNewsEpisode? {
        val id = card.id?.takeIf { it.isNotBlank() } ?: return null
        val title = card.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val duration = card.duration?.takeIf { it > 0L } ?: return null
        if (card.playable == false) return null
        if (card.type != "video" && card.contentType != "film") return null

        return ServusNewsEpisode(
            id = id,
            title = title,
            showName = card.showName?.takeIf { it.isNotBlank() } ?: showTitle,
            description = card.longDescription?.takeIf { it.isNotBlank() }
                ?: card.shortDescription?.takeIf { it.isNotBlank() },
            durationMillis = duration,
            publishedAtMillis = publishedAtMillis(card, nowMillis),
            artworkUri = landscapeArtwork(id, card.mediaResources),
            showId = showId,
            logoUri = showLogoUri,
            categoryId = categoryId,
            categoryTitle = categoryTitle,
            contentType = card.contentType,
        )
    }

    /** Prefer full episodes for a show. If a show exposes no episode/film cards, keep playable videos. */
    fun selectChannelEpisodes(episodes: List<ServusNewsEpisode>): List<ServusNewsEpisode> {
        val deduplicated = episodes
            .distinctBy { it.id }
            .groupBy(::episodeKey)
            .values
            .mapNotNull { values -> values.maxByOrNull { it.durationMillis } }
            .sortedByDescending { it.publishedAtMillis }
        val full = deduplicated.filter { it.contentType == "episode" || it.contentType == "film" }
        return (full.ifEmpty { deduplicated }).take(MAX_SHOW_EPISODES)
    }

    fun liveProgram(card: ServusCardDto): ServusLiveProgram? {
        val start = parseInstant(card.startTime) ?: return null
        val end = parseInstant(card.endTime) ?: return null
        val title = card.title?.takeIf { it.isNotBlank() } ?: return null
        return ServusLiveProgram(
            id = card.id,
            title = title,
            subtitle = card.subheading?.takeIf { it.isNotBlank() },
            description = card.longDescription?.takeIf { it.isNotBlank() }
                ?: card.shortDescription?.takeIf { it.isNotBlank() },
            startAtMillis = start,
            endAtMillis = end,
        )
    }

    fun nextOffset(next: String?): Int? = nextOffsetPattern.find(next.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    fun titleTreatment(id: String, resources: List<String>): String? {
        val resource = resources.firstOrNull { name ->
            name.contains("title_treatment", ignoreCase = true)
        } ?: resources.firstOrNull { name ->
            name.contains("treatment", ignoreCase = true) &&
                !name.contains("background", ignoreCase = true)
        } ?: return null
        return "${ServusNetwork.ARTWORK_BASE_URL}$id/$resource/f_webp,c_fill,h_180,q_75?namespace=stv&refresh=true"
    }

    fun landscapeArtwork(id: String, resources: List<String>): String? = artwork(id, resources, "landscape", 1280)

    fun squareArtwork(id: String, resources: List<String>): String? = artwork(id, resources, "square", 600)

    private fun artwork(id: String, resources: List<String>, type: String, width: Int): String? {
        val resource = resources.firstOrNull { name ->
            name.contains(type, ignoreCase = true) &&
                !name.contains("cover_", ignoreCase = true) &&
                !name.contains("treatment_", ignoreCase = true)
        } ?: return null
        return "${ServusNetwork.ARTWORK_BASE_URL}$id/$resource/f_webp,c_fill,w_$width,q_72?namespace=stv&refresh=true"
    }

    private fun episodeKey(episode: ServusNewsEpisode): String {
        val minute = episode.publishedAtMillis / 60_000L
        return "${episode.showId.orEmpty()}|$minute|${normalize(episode.title)}"
    }

    private fun publishedAtMillis(card: ServusCardDto, nowMillis: Long): Long {
        parseInstant(card.sunriseTimestamp)?.let { return it }
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

    private fun parseInstant(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        try {
            return Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            // The guide API has historically also emitted ISO timestamps without a zone suffix.
        }
        return runCatching {
            LocalDateTime.parse(value.take(19)).toInstant(ZoneOffset.UTC).toEpochMilli()
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

    private fun normalize(value: String): String = value
        .lowercase(Locale.GERMAN)
        .replace(Regex("""[^a-z0-9äöüß]+"""), "-")
        .trim('-')
        .take(100)

    private const val MAX_SHOW_EPISODES = 18
}
