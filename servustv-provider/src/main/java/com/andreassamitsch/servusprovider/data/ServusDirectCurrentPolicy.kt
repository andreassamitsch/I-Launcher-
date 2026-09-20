package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCardDto

/** Editorial show collections: never mistake recommendations or individual news clips for full editions. */
internal object ServusDirectCurrentPolicy {
    private const val WEGSCHEIDER_SHOW_ID = "AA-1Q66UK71N1W11"

    fun candidatesForCollection(
        rootShowId: String,
        label: String?,
        cards: List<ServusCardDto>,
    ): List<ServusCurrentCandidate> {
        val kind = when (rootShowId) {
            ServusBranding.NEWS_SHOW_ID -> ServusCatalogPolicy.contentKindForCollection(
                rootShowId, ServusBranding.NEWS_SHOW_NAME, label,
            )
            WEGSCHEIDER_SHOW_ID -> if (label?.trim().equals("Aktuelle Sendungen", ignoreCase = true)) {
                ServusContentKind.WEGSCHEIDER
            } else null
            else -> null
        } ?: return emptyList()

        return cards.mapNotNull { card ->
            val id = card.id?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (card.title.isNullOrBlank() || card.playable == false ||
                !(card.type == "video" || card.contentType in listOf("episode", "clip", "film"))
            ) return@mapNotNull null
            ServusCurrentCandidate(id, kind)
        }.distinctBy { it.id }
    }
}
