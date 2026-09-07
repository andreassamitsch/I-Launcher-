package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCardDto
import com.andreassamitsch.servusprovider.api.ServusMediaResourceDto
import com.andreassamitsch.servusprovider.api.ServusNetwork
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Locale

/** A playable card together with the collection that supplied it. */
data class ServusSourcedCard(
    val card: ServusCardDto,
    val sourceCollectionId: String? = null,
    val sourceCollectionLabel: String? = null,
    val contentKindHint: ServusContentKind? = null,
    /** Actual editorial parent of the collection, which can differ from the opened umbrella page. */
    val contentShowId: String? = null,
    val contentShowTitle: String? = null,
)

object ServusCatalogPolicy {
    private val nextOffsetPattern = Regex("""(?:[?&]offset=)(\d+)""")
    private val episodeNumberPattern = Regex("""(?i)\bepisode\s+(\d+)\b""")
    private val seasonNumberPattern = Regex("""(?i)\bseason\s+(\d+)\b""")

    fun isShowCard(card: ServusCardDto): Boolean =
        card.id?.isNotBlank() == true &&
            card.title?.isNotBlank() == true &&
            card.type == "page" &&
            card.contentType != "film"

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
        val resources = mergeMediaResources(card.mediaResources, detail?.mediaResources.orEmpty())
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

    fun contentKindForCollection(
        ownerShowId: String?,
        ownerShowTitle: String?,
        collectionLabel: String?,
    ): ServusContentKind? {
        val label = normalizeWords(collectionLabel.orEmpty())
        val owner = normalizeWords(ownerShowTitle.orEmpty())
        return when {
            label.contains("servus nachrichten") && label.contains("90 sekunden") ->
                ServusContentKind.NEWS_90_SECONDS
            label.contains("19 20") && label.contains("nachrichten") -> ServusContentKind.FULL_NEWS
            ownerShowId == ServusBranding.NEWS_90_SECONDS_SHOW_ID && label == "aktuelle sendungen" ->
                ServusContentKind.NEWS_90_SECONDS
            owner.contains("nachrichten in 90 sekunden") && label == "aktuelle sendungen" ->
                ServusContentKind.NEWS_90_SECONDS
            else -> null
        }
    }

    /** Strong API parent identity wins over all text heuristics. */
    fun belongsToShow(card: ServusCardDto, showId: String, showTitle: String): Boolean {
        val playlist = "$showId:all_episodes"
        if (card.deeplinkPlaylist == playlist || card.nextPlaylist == playlist) return true

        val targetTitle = normalizeWords(showTitle)
        if (targetTitle.isBlank()) return false
        card.showName?.takeIf { it.isNotBlank() }?.let { suppliedShow ->
            return normalizeWords(suppliedShow) == targetTitle
        }
        if (card.collections.any { it.id == showId }) return true

        val episodeTitle = normalizeWords(card.title.orEmpty())
        return episodeTitle.isNotBlank() && episodeTitle.contains(targetTitle)
    }

    fun belongsToShow(candidate: ServusSourcedCard, showId: String, showTitle: String): Boolean {
        candidate.contentShowId?.let { parentId ->
            return parentId == showId
        }
        return when (candidate.contentKindHint) {
            ServusContentKind.NEWS_90_SECONDS -> showId == ServusBranding.NEWS_90_SECONDS_SHOW_ID
            ServusContentKind.FULL_NEWS -> showId == ServusBranding.NEWS_SHOW_ID ||
                belongsToShow(candidate.card, showId, showTitle)
            ServusContentKind.WEGSCHEIDER, null -> belongsToShow(candidate.card, showId, showTitle)
        }
    }

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
                val effectiveId = candidate.contentShowId ?: showId
                val effectiveTitle = candidate.contentShowTitle ?: showTitle
                card.id?.isNotBlank() == true &&
                    card.title?.isNotBlank() == true &&
                    card.playable != false &&
                    isVideoLike(card) &&
                    belongsToShow(candidate, effectiveId, effectiveTitle)
            }
            .distinctBy { it.card.id }
            .toList()

        val full = eligible.filter { isFullEpisodeCard(it.card) }
        val unknown = eligible.filter { it.card.contentType.isNullOrBlank() }
        val fallback = eligible.filterNot { isFullEpisodeCard(it.card) || it.card.contentType.isNullOrBlank() }
        return (full + unknown + fallback).distinctBy { it.card.id }.take(limit)
    }

    private fun prioritizeCards(cards: List<ServusCardDto>, limit: Int): List<ServusCardDto> {
        val full = cards.filter(::isFullEpisodeCard)
        val unknown = cards.filter { it.contentType.isNullOrBlank() }
        val fallback = cards.filterNot { isFullEpisodeCard(it) || it.contentType.isNullOrBlank() }
        return (full + unknown + fallback).distinctBy { it.id }.take(limit)
    }

    fun mergeEpisodeProduct(collectionCard: ServusCardDto, detail: ServusCardDto): ServusCardDto =
        ServusCardDto(
            id = prefer(detail.id, collectionCard.id),
            type = prefer(detail.type, collectionCard.type),
            contentType = prefer(detail.contentType, collectionCard.contentType),
            title = prefer(detail.title, collectionCard.title),
            showName = prefer(detail.showName, collectionCard.showName),
            subheading = prefer(detail.subheading, collectionCard.subheading),
            label = prefer(detail.label, collectionCard.label),
            shortDescription = prefer(detail.shortDescription, collectionCard.shortDescription),
            longDescription = prefer(detail.longDescription, collectionCard.longDescription),
            duration = detail.duration ?: collectionCard.duration,
            formattedDuration = prefer(detail.formattedDuration, collectionCard.formattedDuration),
            playable = detail.playable ?: collectionCard.playable,
            sunriseTimestamp = prefer(detail.sunriseTimestamp, collectionCard.sunriseTimestamp),
            sunsetTimestamp = prefer(detail.sunsetTimestamp, collectionCard.sunsetTimestamp),
            startTime = prefer(detail.startTime, collectionCard.startTime),
            endTime = prefer(detail.endTime, collectionCard.endTime),
            seasonNumber = detail.seasonNumber
                ?: parseNumber(detail.season, seasonNumberPattern)
                ?: collectionCard.seasonNumber,
            episodeNumber = detail.episodeNumber
                ?: parseNumber(detail.chapter, episodeNumberPattern)
                ?: collectionCard.episodeNumber,
            season = prefer(detail.season, collectionCard.season),
            chapter = prefer(detail.chapter, collectionCard.chapter),
            deeplinkPlaylist = prefer(detail.deeplinkPlaylist, collectionCard.deeplinkPlaylist),
            nextPlaylist = prefer(detail.nextPlaylist, collectionCard.nextPlaylist),
            shareUrl = prefer(detail.shareUrl, collectionCard.shareUrl),
            detailPageId = prefer(detail.detailPageId, collectionCard.detailPageId),
            tags = (detail.tags + collectionCard.tags).distinct(),
            vertical = (detail.vertical + collectionCard.vertical).distinct(),
            mediaResources = mergeMediaResources(collectionCard.mediaResources, detail.mediaResources),
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
        if (card.playable == false || !isVideoLike(card)) return null

        val effectiveShowId = candidate.contentShowId ?: showId
        val effectiveShowTitle = candidate.contentShowTitle ?: showTitle
        if (!belongsToShow(candidate, effectiveShowId, effectiveShowTitle)) return null

        val episode = ServusNewsEpisode(
            id = id,
            title = title,
            showName = card.showName?.takeIf { it.isNotBlank() }
                ?: card.subheading?.takeIf { it.isNotBlank() }
                ?: effectiveShowTitle,
            description = card.longDescription?.takeIf { it.isNotBlank() }
                ?: card.shortDescription?.takeIf { it.isNotBlank() },
            durationMillis = duration,
            publishedAtMillis = ServusSourceTimestampPolicy.resolve(card, nowMillis),
            artworkUri = landscapeArtwork(id, card.mediaResources),
            showId = effectiveShowId,
            logoUri = ServusBranding.logoUriForShow(effectiveShowId, showLogoUri),
            categoryId = categoryId,
            categoryTitle = categoryTitle,
            contentType = card.contentType,
            seasonNumber = card.seasonNumber ?: parseNumber(card.season, seasonNumberPattern),
            episodeNumber = card.episodeNumber ?: parseNumber(card.chapter, episodeNumberPattern),
            sourceCollectionId = candidate.sourceCollectionId,
            sourceCollectionTitle = candidate.sourceCollectionLabel,
            contentKindHint = candidate.contentKindHint,
        )
        return ServusBranding.canonicalizeEpisode(episode)
    }

    fun selectChannelEpisodes(episodes: List<ServusNewsEpisode>): List<ServusNewsEpisode> {
        val deduplicated = episodes
            .distinctBy { it.id }
            .groupBy(::episodeKey)
            .values
            .mapNotNull { values -> values.maxByOrNull { it.durationMillis } }
            .sortedWith(compareByDescending<ServusNewsEpisode> { ServusNewsPolicy.recencyMillis(it) ?: Long.MIN_VALUE })
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
        ?.groupValues?.getOrNull(1)?.toIntOrNull()

    /** Uses the concrete media URL from the API; no resource path is guessed. */
    fun titleTreatment(id: String, resources: Map<String, ServusMediaResourceDto>): String? {
        if (id == ServusBranding.NEWS_90_SECONDS_SHOW_ID && resources.isEmpty()) {
            return ServusBranding.NEWS_90_SECONDS_LOGO_URI
        }
        val entry = preferredResource(
            resources,
            listOf("rbtv_title_treatment_landscape", "rbtv_title_treatment"),
        ) ?: resources.entries.firstOrNull { (name, _) ->
            val lower = name.lowercase(Locale.ROOT)
            (lower.contains("title_treatment") || lower.contains("wordmark") || lower.contains("logo")) &&
                !lower.contains("background")
        }
        val remote = entry?.let { resolveMediaUrl(id, it.key, it.value, BRANDING_TRANSFORM) }
        return ServusBranding.logoUriForShow(id, remote)
    }

    fun landscapeArtwork(id: String, resources: Map<String, ServusMediaResourceDto>): String? {
        val entry = preferredResource(
            resources,
            listOf("rbtv_display_art_landscape", "rbtv_background_landscape"),
        ) ?: return null
        return resolveMediaUrl(id, entry.key, entry.value, LANDSCAPE_TRANSFORM)
    }

    fun squareArtwork(id: String, resources: Map<String, ServusMediaResourceDto>): String? {
        val entry = preferredResource(
            resources,
            listOf("rbtv_display_art_square", "rbtv_background_square"),
        ) ?: return null
        return resolveMediaUrl(id, entry.key, entry.value, SQUARE_TRANSFORM)
    }

    fun portraitArtwork(id: String, resources: Map<String, ServusMediaResourceDto>): String? {
        val entry = preferredResource(
            resources,
            listOf("rbtv_cover_art_portrait", "rbtv_display_art_portrait", "rbtv_background_portrait"),
        ) ?: return null
        return resolveMediaUrl(id, entry.key, entry.value, PORTRAIT_TRANSFORM)
    }

    fun previewVideo(resources: Map<String, ServusMediaResourceDto>): String? =
        resources["short_preview_mp4_high"]?.url?.takeIf { it.isNotBlank() }

    fun mergeMediaResources(
        fallback: Map<String, ServusMediaResourceDto>,
        authoritative: Map<String, ServusMediaResourceDto>,
    ): Map<String, ServusMediaResourceDto> = fallback + authoritative

    private fun preferredResource(
        resources: Map<String, ServusMediaResourceDto>,
        names: List<String>,
    ): Map.Entry<String, ServusMediaResourceDto>? = names.firstNotNullOfOrNull { name ->
        resources.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
    }

    private fun resolveMediaUrl(
        id: String,
        resourceName: String,
        resource: ServusMediaResourceDto,
        transform: String,
    ): String? {
        val apiUrl = resource.url?.trim()?.takeIf { it.isNotBlank() }
        if (apiUrl != null) {
            return if (apiUrl.contains("{im}")) apiUrl.replace("{im}", transform) else apiUrl
        }
        // Compatibility only for legacy API shapes that returned a bare resource name.
        return "${ServusNetwork.ARTWORK_BASE_URL}$id/$resourceName/$transform?namespace=stv&refresh=true"
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
            // Some guide payloads omit a zone suffix.
        }
        return runCatching {
            LocalDateTime.parse(value.take(19)).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    private fun parseNumber(value: String?, pattern: Regex): Int? = value
        ?.let(pattern::find)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    private fun isVideoLike(card: ServusCardDto): Boolean =
        card.type.equals("video", ignoreCase = true) ||
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
    private const val BRANDING_TRANSFORM = "f_webp,c_fit,w_720,h_220,q_85"
    private const val LANDSCAPE_TRANSFORM = "f_webp,c_fill,w_1280,q_72"
    private const val SQUARE_TRANSFORM = "f_webp,c_fill,w_600,q_72"
    private const val PORTRAIT_TRANSFORM = "f_webp,c_fill,w_600,q_72"
}
