package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCardDto
import com.andreassamitsch.servusprovider.api.ServusNetwork
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * A playable card together with the collection that supplied it. Collection context is editorial
 * metadata: ServusTV's generic "Servus Nachrichten" product intentionally links separate rails for
 * 19:20, 90 seconds and individual reports, while the cards themselves can omit show_name entirely.
 */
data class ServusSourcedCard(
    val card: ServusCardDto,
    val sourceCollectionId: String? = null,
    val sourceCollectionLabel: String? = null,
    val contentKindHint: ServusContentKind? = null,
)

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
            logoUri = ServusBranding.logoUriForShow(id, titleTreatment(id, resources)),
            episodes = episodes,
        )
    }

    /**
     * Classifies only collections whose editorial role is explicit enough to be trusted. This is
     * intentionally not a duration heuristic: generic "Einzelbeiträge" can also be around 90s.
     */
    fun contentKindForCollection(
        ownerShowId: String?,
        ownerShowTitle: String?,
        collectionLabel: String?,
    ): ServusContentKind? {
        val label = normalizeWords(collectionLabel.orEmpty())
        val owner = normalizeWords(ownerShowTitle.orEmpty())
        return when {
            label.contains("90 sekunden") -> ServusContentKind.NEWS_90_SECONDS
            label.contains("19 20") && label.contains("nachrichten") -> ServusContentKind.FULL_NEWS
            ownerShowId == ServusBranding.NEWS_90_SECONDS_SHOW_ID &&
                label == "aktuelle sendungen" -> ServusContentKind.NEWS_90_SECONDS
            owner.contains("nachrichten in 90 sekunden") &&
                label == "aktuelle sendungen" -> ServusContentKind.NEWS_90_SECONDS
            else -> null
        }
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

    /** Source-aware membership prevents the generic news page from claiming its 90-second rail. */
    fun belongsToShow(candidate: ServusSourcedCard, showId: String, showTitle: String): Boolean {
        return when (candidate.contentKindHint) {
            ServusContentKind.NEWS_90_SECONDS -> showId == ServusBranding.NEWS_90_SECONDS_SHOW_ID
            ServusContentKind.FULL_NEWS -> showId == ServusBranding.NEWS_SHOW_ID ||
                belongsToShow(candidate.card, showId, showTitle)
            ServusContentKind.WEGSCHEIDER, null -> belongsToShow(candidate.card, showId, showTitle)
        }
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

        return prioritizeCards(eligible, limit)
    }

    /** Same selection, but preserving the source collection and its trusted format hint. */
    fun selectSourcedEpisodeCardsForHydration(
        candidates: List<ServusSourcedCard>,
        showId: String,
        showTitle: String,
        limit: Int,
    ): List<ServusSourcedCard> {
        if (limit <= 0) return emptyList()
        val eligible = candidates.asSequence()
            .filter { candidate ->
                val card = candidate.card
                card.id?.isNotBlank() == true &&
                    card.title?.isNotBlank() == true &&
                    card.playable != false &&
                    isVideoLike(card) &&
                    belongsToShow(candidate, showId, showTitle)
            }
            .distinctBy { it.card.id }
            .toList()

        val full = eligible.filter { isFullEpisodeCard(it.card) }
        val unknown = eligible.filter { it.card.contentType.isNullOrBlank() }
        val fallback = eligible.filterNot { candidate ->
            isFullEpisodeCard(candidate.card) || candidate.card.contentType.isNullOrBlank()
        }
        return (full + unknown + fallback)
            .distinctBy { it.card.id }
            .take(limit)
    }

    private fun prioritizeCards(cards: List<ServusCardDto>, limit: Int): List<ServusCardDto> {
        val full = cards.filter(::isFullEpisodeCard)
        val unknown = cards.filter { it.contentType.isNullOrBlank() }
        val fallback = cards.filterNot { card ->
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
    ): ServusNewsEpisode? = toShowEpisode(
        candidate = ServusSourcedCard(card = card),
        showId = showId,
        showTitle = showTitle,
        categoryId = categoryId,
        categoryTitle = categoryTitle,
        showLogoUri = showLogoUri,
        nowMillis = nowMillis,
    )

    fun toShowEpisode(
        candidate: ServusSourcedCard,
        showId: String,
        showTitle: String,
        categoryId: String,
        categoryTitle: String,
        showLogoUri: String?,
        nowMillis: Long,
    ): ServusNewsEpisode? {
        val card = candidate.card
        val id = card.id?.takeIf { it.isNotBlank() } ?: return null
        val title = card.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val duration = card.duration?.takeIf { it > 0L } ?: return null
        if (card.playable == false) return null
        if (card.type != "video" && card.contentType != "film") return null
        if (!belongsToShow(candidate, showId, showTitle)) return null

        val episode = ServusNewsEpisode(
            id = id,
            title = title,
            showName = card.showName?.takeIf { it.isNotBlank() } ?: showTitle,
            description = card.longDescription?.takeIf { it.isNotBlank() }
                ?: card.shortDescription?.takeIf { it.isNotBlank() },
            durationMillis = duration,
            publishedAtMillis = ServusSourceTimestampPolicy.resolve(card, nowMillis),
            artworkUri = landscapeArtwork(id, card.mediaResources),
            showId = showId,
            logoUri = ServusBranding.logoUriForShow(showId, showLogoUri),
            categoryId = categoryId,
            categoryTitle = categoryTitle,
            contentType = card.contentType,
            contentKindHint = candidate.contentKindHint,
        )
        return ServusBranding.canonicalizeEpisode(episode)
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
        if (id == ServusBranding.NEWS_90_SECONDS_SHOW_ID) {
            return ServusBranding.NEWS_90_SECONDS_LOGO_URI
        }
        val resource = resources.firstOrNull { name ->
            name.contains("title_treatment", ignoreCase = true)
        } ?: resources.firstOrNull { name ->
            name.contains("treatment", ignoreCase = true) &&
                !name.contains("background", ignoreCase = true)
        }
        val remote = resource?.let {
            "${ServusNetwork.ARTWORK_BASE_URL}$id/$it/f_webp,c_fill,h_180,q_75?namespace=stv&refresh=true"
        }
        return ServusBranding.logoUriForShow(id, remote)
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

    const val NEWS_90_SECONDS_SHOW_ID = ServusBranding.NEWS_90_SECONDS_SHOW_ID
    const val NEWS_SHOW_ID = ServusBranding.NEWS_SHOW_ID
    private const val MAX_SHOW_EPISODES = 18
}
