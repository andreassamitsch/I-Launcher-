package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCardDto
import com.andreassamitsch.servusprovider.api.ServusNetwork
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Locale

object ServusCatalogPolicy {
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

    /**
     * A show product can reference editorial/recommendation collections that contain videos from
     * other shows. Never attach every playable card blindly to the opened show.
     *
     * Strong membership evidence is, in order: an exact normalised `show_name`, an explicit parent
     * collection reference to the show ID, or the target show title contained in the episode title.
     * If none of those is present, reject the card rather than showing a confidently wrong episode.
     */
    fun belongsToShow(card: ServusCardDto, showId: String, showTitle: String): Boolean {
        val targetTitle = normalizeWords(showTitle)
        if (targetTitle.isBlank()) return false

        card.showName?.takeIf { it.isNotBlank() }?.let { suppliedShow ->
            return normalizeWords(suppliedShow) == targetTitle
        }
        if (card.collections.any { it.id == showId }) return true

        val episodeTitle = normalizeWords(card.title.orEmpty())
        return episodeTitle.isNotBlank() && episodeTitle.contains(targetTitle)
    }

    /**
     * Selects the small set of collection cards whose product details are worth hydrating.
     *
     * Collection responses are discovery/order sources and can omit duration, content type or the
     * VOD `sunrise_timestamp`. We therefore keep candidates even when those detail-only fields are
     * missing, but still require strong show membership and a video-like identity. Known full
     * episodes/films come first, unknown content types second and explicit clips last. Relative API
     * order is preserved inside every group.
     */
    fun selectEpisodeCardsForHydration(
        cards: List<ServusCardDto>,
        showId: String,
        showTitle: String,
        limit: Int,
    ): List<ServusCardDto> {
        if (limit <= 0) return emptyList()

        val eligible = cards.asSequence()
            .filter { card ->
                card.id?.isNotBlank() == true &&
                    card.title?.isNotBlank() == true &&
                    card.playable != false &&
                    isVideoLike(card) &&
                    belongsToShow(card, showId, showTitle)
            }
            .distinctBy { it.id }
            .toList()

        val full = eligible.filter(::isFullEpisodeCard)
        val unknown = eligible.filter { it.contentType.isNullOrBlank() }
        val fallback = eligible.filterNot { card ->
            isFullEpisodeCard(card) || card.contentType.isNullOrBlank()
        }

        return (full + unknown + fallback)
            .distinctBy { it.id }
            .take(limit)
    }

    /**
     * Product details are authoritative for VOD availability and detailed metadata, while the
     * collection card can carry show membership/artwork fields that the product response omits.
     * Merge both instead of replacing the collection card so hydration cannot accidentally detach
     * a valid episode from its show.
     */
    fun mergeEpisodeProduct(collectionCard: ServusCardDto, detail: ServusCardDto): ServusCardDto =
        ServusCardDto(
            id = prefer(detail.id, collectionCard.id),
            type = prefer(detail.type, collectionCard.type),
            contentType = prefer(detail.contentType, collectionCard.contentType),
            title = prefer(detail.title, collectionCard.title),
            showName = prefer(detail.showName, collectionCard.showName),
            subheading = prefer(detail.subheading, collectionCard.subheading),
            shortDescription = prefer(detail.shortDescription, collectionCard.shortDescription),
            longDescription = prefer(detail.longDescription, collectionCard.longDescription),
            duration = detail.duration ?: collectionCard.duration,
            playable = detail.playable ?: collectionCard.playable,
            sunriseTimestamp = prefer(detail.sunriseTimestamp, collectionCard.sunriseTimestamp),
            sunsetTimestamp = prefer(detail.sunsetTimestamp, collectionCard.sunsetTimestamp),
            startTime = prefer(detail.startTime, collectionCard.startTime),
            endTime = prefer(detail.endTime, collectionCard.endTime),
            seasonNumber = detail.seasonNumber ?: collectionCard.seasonNumber,
            episodeNumber = detail.episodeNumber ?: collectionCard.episodeNumber,
            mediaResources = (detail.mediaResources + collectionCard.mediaResources).distinct(),
            collections = (detail.collections + collectionCard.collections).distinct(),
        )

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
        if (!belongsToShow(card, showId, showTitle)) return null

        return ServusNewsEpisode(
            id = id,
            title = title,
            showName = card.showName?.takeIf { it.isNotBlank() } ?: showTitle,
            description = card.longDescription?.takeIf { it.isNotBlank() }
                ?: card.shortDescription?.takeIf { it.isNotBlank() },
            durationMillis = duration,
            publishedAtMillis = ServusSourceTimestampPolicy.resolve(card, nowMillis),
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
            .sortedWith(
                compareByDescending<ServusNewsEpisode> { ServusNewsPolicy.recencyMillis(it) ?: Long.MIN_VALUE },
            )
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
        if (id == NEWS_90_SECONDS_SHOW_ID) {
            val displayArt = resources.firstOrNull { name ->
                name.equals("rbtv_display_art_landscape", ignoreCase = true)
            }
            if (displayArt != null) {
                return "${ServusNetwork.ARTWORK_BASE_URL}$id/$displayArt/f_webp,c_fill,w_640,q_80?namespace=stv&refresh=true"
            }
        }
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
        val minute = ServusNewsPolicy.recencyMillis(episode)?.div(60_000L)?.toString() ?: "unknown"
        return "${episode.showId.orEmpty()}|$minute|${normalize(episode.title)}"
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

    private fun isVideoLike(card: ServusCardDto): Boolean =
        card.type == "video" ||
            card.contentType.equals("episode", ignoreCase = true) ||
            card.contentType.equals("film", ignoreCase = true) ||
            card.contentType.equals("clip", ignoreCase = true)

    private fun isFullEpisodeCard(card: ServusCardDto): Boolean =
        card.contentType.equals("episode", ignoreCase = true) ||
            card.contentType.equals("film", ignoreCase = true)

    private fun prefer(primary: String?, fallback: String?): String? =
        primary?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() }

    private fun normalizeWords(value: String): String = value
        .lowercase(Locale.GERMAN)
        .replace('–', '-')
        .replace(Regex("""[^a-z0-9äöüß]+"""), " ")
        .trim()

    private fun normalize(value: String): String = value
        .lowercase(Locale.GERMAN)
        .replace(Regex("""[^a-z0-9äöüß]+"""), "-")
        .trim('-')
        .take(100)

    const val NEWS_90_SECONDS_SHOW_ID = "AAYGF2URW6ALQYE42IJK"
    private const val MAX_SHOW_EPISODES = 18
}
