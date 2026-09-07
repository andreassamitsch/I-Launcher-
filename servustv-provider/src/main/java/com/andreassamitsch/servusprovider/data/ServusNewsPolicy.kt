package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCardDto
import com.andreassamitsch.servusprovider.api.ServusMediaResourceDto
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Supported ServusTV formats that are intentionally exposed by the fast Aktuelles feed. */
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

    private val excludedFullNewsFragments = listOf(
        "90 sekunden",
        "90-sekunden",
        "kurzmeldung",
        "newsflash",
        "news flash",
    )

    fun couldBelongToSupportedContent(card: ServusCardDto): Boolean {
        if (card.id.isNullOrBlank()) return false
        val text = searchableText(card)
        return contentKind(text) != null ||
            text.contains("servus nachrichten") ||
            text.contains("wegscheider")
    }

    fun contentKind(card: ServusCardDto): ServusContentKind? = contentKind(searchableText(card))

    fun toFullNewsEpisode(
        card: ServusCardDto,
        nowMillis: Long = System.currentTimeMillis(),
    ): ServusNewsEpisode? {
        if (contentKind(card) != ServusContentKind.FULL_NEWS) return null
        return toSupportedEpisode(card, nowMillis)
    }

    fun toSupportedEpisode(
        card: ServusCardDto,
        nowMillis: Long = System.currentTimeMillis(),
        contentKindHint: ServusContentKind? = null,
    ): ServusNewsEpisode? {
        val id = card.id?.takeIf { it.isNotBlank() } ?: return null
        val title = card.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val text = searchableText(card)
        val kind = contentKindHint ?: contentKind(text) ?: return null
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

        val episode = ServusNewsEpisode(
            id = id,
            title = title,
            showName = canonicalShowName(kind, card),
            description = card.longDescription?.takeIf { it.length > 20 }
                ?: card.shortDescription?.takeIf { it.isNotBlank() },
            durationMillis = duration,
            publishedAtMillis = ServusSourceTimestampPolicy.resolve(card, nowMillis),
            artworkUri = landscapeArtwork(id, card.mediaResources),
            seasonNumber = card.seasonNumber,
            episodeNumber = card.episodeNumber,
            contentKindHint = kind,
        )
        return ServusBranding.canonicalizeEpisode(episode)
    }

    fun contentKind(episode: ServusNewsEpisode): ServusContentKind? {
        episode.contentKindHint?.let { return it }
        if (episode.showId == ServusBranding.NEWS_90_SECONDS_SHOW_ID) {
            return ServusContentKind.NEWS_90_SECONDS
        }
        return contentKind(
            listOfNotNull(episode.title, episode.showName)
                .joinToString(" ")
                .lowercase(Locale.GERMAN)
                .replace('–', '-'),
        )
    }

    fun displayLabel(episode: ServusNewsEpisode): String = when (contentKind(episode)) {
        ServusContentKind.FULL_NEWS -> "Servus Nachrichten 19:20"
        ServusContentKind.NEWS_90_SECONDS -> "Servus Nachrichten in 90 Sekunden"
        ServusContentKind.WEGSCHEIDER -> "Der Wegscheider"
        null -> episode.showName?.takeIf { it.isNotBlank() } ?: "ServusTV"
    }

    fun recencyMillis(episode: ServusNewsEpisode): Long? =
        episode.publishedAtMillis ?: episode.observedAvailableAtMillis

    fun contentKey(episode: ServusNewsEpisode): String {
        val localDateTime = recencyMillis(episode)
            ?.let(Instant::ofEpochMilli)
            ?.atZone(ZoneId.systemDefault())
        val date = localDateTime?.toLocalDate()
        val normalizedTitle = normalizeTitle(episode.title)
        return when (contentKind(episode)) {
            ServusContentKind.FULL_NEWS -> date?.let { "full-news-$it" }
                ?: "full-news-unknown-$normalizedTitle"
            ServusContentKind.NEWS_90_SECONDS -> {
                val minute = localDateTime?.withSecond(0)?.withNano(0)?.toLocalTime()
                if (date != null && minute != null) {
                    "news-90-$date-$minute-$normalizedTitle"
                } else {
                    "news-90-unknown-$normalizedTitle"
                }
            }
            ServusContentKind.WEGSCHEIDER -> date?.let { "wegscheider-$it-$normalizedTitle" }
                ?: "wegscheider-unknown-$normalizedTitle"
            null -> date?.let { "unknown-$it-$normalizedTitle" }
                ?: "unknown-${episode.showId.orEmpty()}-$normalizedTitle"
        }
    }

    fun deduplicateEpisodes(episodes: List<ServusNewsEpisode>): List<ServusNewsEpisode> {
        return episodes
            .distinctBy { it.id }
            .groupBy(::contentKey)
            .values
            .mapNotNull { candidates ->
                candidates.maxWithOrNull(
                    compareBy<ServusNewsEpisode> { recencyMillis(it) ?: Long.MIN_VALUE }
                        .thenBy { it.durationMillis },
                )
            }
            .sortedWith(
                compareByDescending<ServusNewsEpisode> { recencyMillis(it) ?: Long.MIN_VALUE },
            )
    }

    fun deduplicateEditions(episodes: List<ServusNewsEpisode>): List<ServusNewsEpisode> =
        deduplicateEpisodes(episodes)

    fun editionKey(episode: ServusNewsEpisode): String = contentKey(episode)

    fun landscapeArtwork(
        id: String,
        resources: Map<String, ServusMediaResourceDto>,
    ): String? = ServusCatalogPolicy.landscapeArtwork(id, resources)

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
            ServusContentKind.FULL_NEWS -> supplied ?: ServusBranding.NEWS_SHOW_NAME
            ServusContentKind.NEWS_90_SECONDS -> ServusBranding.NEWS_90_SECONDS_SHOW_NAME
            ServusContentKind.WEGSCHEIDER -> "Der Wegscheider"
        }
    }

    private fun searchableText(card: ServusCardDto): String = listOfNotNull(
        card.title,
        card.showName,
        card.subheading,
        card.label,
        card.shortDescription,
    ).joinToString(" ")
        .lowercase(Locale.GERMAN)
        .replace('–', '-')

    private fun normalizeTitle(value: String): String = value
        .lowercase(Locale.GERMAN)
        .replace(Regex("""[^a-z0-9äöüß]+"""), "-")
        .trim('-')
        .take(96)
}
