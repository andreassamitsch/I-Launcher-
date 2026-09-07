package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.SearchResponseDto
import com.andreassamitsch.servusprovider.api.ServusCardDto
import java.util.Locale

/** API-driven classification of show-page collections. */
object ServusCollectionPolicy {
    fun classify(
        response: SearchResponseDto,
        ownerShowId: String,
        ownerShowTitle: String,
    ): ServusCollectionRole {
        val label = normalize(response.label.orEmpty())
        val listType = normalize(response.listType.orEmpty())
        val type = normalize(response.type.orEmpty())

        if (listType == "playnet" || type == "small promo" || label.startsWith("mehr zu")) {
            return ServusCollectionRole.INFO
        }
        if (isRecommendationLabel(label)) return ServusCollectionRole.RECOMMENDATION

        val playableCards = response.cards.filter(::isVideoLike)
        if (playableCards.isEmpty()) return ServusCollectionRole.UNKNOWN
        if (isKnownChildCollection(label)) return ServusCollectionRole.CONTENT

        val matchingCards = playableCards.count { card ->
            hasOwnerIdentity(card, ownerShowId, ownerShowTitle)
        }
        return if (matchingCards > 0 && matchingCards * 2 >= playableCards.size) {
            ServusCollectionRole.CONTENT
        } else {
            ServusCollectionRole.UNKNOWN
        }
    }

    fun contentShowId(ownerShowId: String, response: SearchResponseDto): String {
        val label = normalize(response.label.orEmpty())
        return when {
            label.contains("servus nachrichten in 90 sekunden") -> ServusBranding.NEWS_90_SECONDS_SHOW_ID
            label.contains("servus wetter in 90 sekunden") -> ServusBranding.WEATHER_90_SECONDS_SHOW_ID
            else -> ownerShowId
        }
    }

    fun contentShowTitle(ownerShowTitle: String, response: SearchResponseDto): String {
        val label = normalize(response.label.orEmpty())
        return when {
            label.contains("servus nachrichten in 90 sekunden") -> ServusBranding.NEWS_90_SECONDS_SHOW_NAME
            label.contains("servus wetter in 90 sekunden") -> ServusBranding.WEATHER_90_SECONDS_SHOW_NAME
            else -> ownerShowTitle
        }
    }

    fun displayTitle(response: SearchResponseDto, fallback: String?): String =
        response.label?.trim()?.takeIf { it.isNotBlank() }
            ?: fallback?.trim()?.takeIf { it.isNotBlank() }
            ?: "Weitere Videos"

    private fun hasOwnerIdentity(card: ServusCardDto, showId: String, showTitle: String): Boolean {
        val playlist = "$showId:all_episodes"
        if (card.deeplinkPlaylist == playlist || card.nextPlaylist == playlist) return true
        val target = normalize(showTitle)
        return sequenceOf(card.showName, card.subheading, card.label)
            .filterNotNull()
            .map(::normalize)
            .any { it == target }
    }

    private fun isKnownChildCollection(label: String): Boolean =
        label.contains("servus nachrichten in 90 sekunden") ||
            label.contains("servus wetter in 90 sekunden")

    private fun isRecommendationLabel(label: String): Boolean =
        label.contains("das könnte ihnen auch gefallen") ||
            label.contains("das koennte ihnen auch gefallen") ||
            label.contains("empfehlungen") ||
            label.contains("ähnliche") ||
            label.contains("aehnliche") ||
            label.contains("related")

    private fun isVideoLike(card: ServusCardDto): Boolean =
        card.playable != false && (
            card.type.equals("video", ignoreCase = true) ||
                card.contentType.equals("episode", ignoreCase = true) ||
                card.contentType.equals("film", ignoreCase = true) ||
                card.contentType.equals("clip", ignoreCase = true)
            )

    private fun normalize(value: String): String = value
        .lowercase(Locale.GERMAN)
        .replace('–', '-')
        .replace(Regex("""[^a-z0-9äöüß]+"""), " ")
        .trim()
}
